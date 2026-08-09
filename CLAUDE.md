# MotionSound — AI Stem Separation Music Player

## Status
- Android/Kotlin app (compileSdk 35, minSdk 34, Java 17, 100% Jetpack Compose Material 3)
- AI stem separation: htdemucs FP16 ONNX via ONNX Runtime **CPU** EP (arm64/armeabi-v7a)
- Foreground service started at app launch from `MotionSoundApp.onCreate`

## Architecture
- `StemPlayerService` — single foreground `mediaPlayback` service (START_STICKY): decode → separate → cache → play, seek, queue, pre-cache, sensors, Sound Drive. MediaStyle notification with prev/play-pause/next PendingIntents, refcounted partial wake lock, audio focus, headset plug/unplug auto-resume/auto-pause. Both `PlayerViewModel` and `DriveViewModel` start + bind it; `MotionSoundApp` starts it at boot of process
- `StemSeparationEngine` — ONNX Runtime CPU: 2×4-thread OrtSessions for parallel chunk inference (falls back to 1×8-thread), hann-window overlap-add via mmap temp files, hann + sum normalization; chunk = 343980 samples, hop = chunk − chunk/4; input `mix` (1,2,343980) → output `stems` (1,4,2,343980); model copied from `assets/models/` to cacheDir, or downloaded from HuggingFace if missing (validate ≥150 MB)
- `StemMixer` — streaming AudioTrack (PCM float 44.1 kHz stereo, 8192-frame mix buffer, audio thread priority): per-stem volume/filter/pan, master LPF + HPF, reverb (6 comb + 4 allpass), tremolo, beat-synced vocal gate, 512-frame fade in/out, `onTrackEnded` callback; `@Volatile` params set from sensor/UI threads
- `AudioDecoder` — MediaExtractor+MediaCodec → short → Float (÷32768), mono→stereo upmix, linear-interp resample to 44100
- `StemAnalyzer` — drums+bass RMS block (2048) beat detection (threshold mean+1.1σ, 22050 min spacing) + 16-block section energy → drives vocal gating in mixer
- `StemCache` — SHA-256 key (16 hex) per URI, 4 `.raw` float32 files per song in `cacheDir/stems_cache`; mmap read/write
- `SensorDriveMapper` — IMU (game rotation vector preferred, linear accel, gyro yaw rate, raw accel fallback, barometer) + GPS (speed 1 s, bearing for world-frame alignment) → driving metrics (speed, accel/brake/corner intensity, driving state, road roughness, ambient mood by time-of-day, hill grade, regen/friction brake) → `SoundDriveProcessor.update()` → `StemMixer` params; updates UI StateFlow on sensor callback (no loop)
- `SoundDriveProcessor` — 3 modes (BALANCED/DYNAMIC/IMMERSIVE) × intensity × sensor profile (SPORTY/DYNAMIC/RELAXED); knob-based layered envelope per stem (enter/full thresholds, kick delays, build & lapse, hysteresis, regen-retreat, pace scaling), gestures (ACCEL_BURST/BRAKE_HIT/CORNER_PEAK/BUMP_HIT/TUNNEL_ENTRY), idle muffling, roughness→tremolo, corner/brake→reverb, corner→pan; when disabled resets all volumes/DSP to neutral
- `StemDsp` — `BiquadFilter` (DF1 LPF/HPF, bypass at bounds, coefficient smoothing 0.03), `StemFxChain` (vol+pan smoothing + filter accumulate), `Reverb` (6 comb + 4 allpass), `Tremolo`
- `SoundDriveConfig` — `SoundDriveMode`(3 values, NO CUSTOM), `GestureType`(5), `SensorProfile`(3), `paramsForMode()` factory; persisted with volumes/loop in DataStore (`motionsound_prefs`)
- UI — `MainScreen`: 4 tabs (Drive/Player/Songs/Settings) + mini now-playing card; `DriveScreen`: idle layout (speedometer, driving state, gesture/mood/hill badges, model progress, Sound Drive panel) ↔ fullscreen immersive moving layout (auto at >5 km/h enter, <1 km/h sleep, manual toggle; hides system bars); `SongListScreen`: Songs/Playlists tabs, search, pull-to-refresh, playlist detail with shuffle + batch pre-separation; `SettingsScreen`: theme (dark/AMOLED), model status, stem cache size + clear, export debug log via FileProvider
- Data: `SongRepository` (MediaStore, ≤1000 songs, IS_MUSIC), `PlaylistRepository` (JSON in filesDir, atomic tmp+rename), `SoundPrefsStore` + `ThemeManager` (shared `motionsound_prefs` DataStore); models `Song(id,title,artist,durationMs,albumArtUri,uri)`, `Playlist(id,name,songIds,createdAt)`

## Model
- HuggingFace `StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx` (158 MB, FP16)
- Bundled in repo at `app/src/main/assets/models/htdemucs_fp16weights.onnx` via Git LFS (also cached in cacheDir)
- Missing/corrupt → download with progress; not usable → playback blocked ("Model not loaded yet"), stems cache still works
- `noCompress "onnx"`, proguard keeps `ai.onnxruntime.**`

## Key Decisions
- Chunk = 343980 samples (model spec), hop = chunk − chunk/4, hann window + sum-normalization overlap-add
- Input tensor name `mix`, output `stems` (actual model spec)
- Two CPU sessions/parallel inference for chunk-level speedup; throttled flag slows separation while music plays
- No NNAPI: CPU EP only (NNAPI dropped)
- Service-level `StemUiState` broadcast → both ViewModels; manual volumes persisted
- Separation runs in service on `Dispatchers.IO` job; decode on IO, infer on Default with limited parallelism
- No x86 targets — ABI splits arm64-v8a + armeabi-v7a only (no universal APK)

## Files
- `app/src/main/java/com/motionsound/stem/` — StemPlayerService, StemSeparationEngine, StemMixer, StemAnalysis/Analyzer, StemCache, StemConfig, AudioDecoder, SensorDriveMapper, AppLogger, StemUiState (10 files)
- `app/src/main/java/com/motionsound/sounddrive/` — SoundDriveConfig, Sound, StemDsp (BiquadFilter/StemFxChain/Reverb/Tremolo) (3 files)
- `app/src/main/java/com/motionsound/drive/` — DriveState (DrivingState/VehiclePreset enums), DriveViewModel
- `app/src/main/java/com/motionsound/viewmodel/PlayerViewModel.kt` — player state, song/queue/playlists, shuffle/loop, position polling
- `app/src/main/java/com/motionsound/ui/screens/` — MainScreen, DriveScreen, SongsListScreen, PlayerScreen, Setting, OnboardingScreen
- `app/src/main/java/com/motionsound/ui/components/` — DriveDashboard (SpeedGauge + badges), DotSlider, PlayerControls, SongItem, PlaylistCard, AddToPlaylistDialog, SettingsCard
- `app/src/main/java/com/motionsound/data/` — SongRepository, PlaylistRepository, SoundPrefsDataStore, ThemeManager

## Constraints
- NO local Gradle builds — CI only; fix from CI logs
- NO x86/x86_64 — ONNX Runtime needs arm64/armerab-v7a device (splits)
- release APKs ≥220 MB (158 MB model + native libs)
- minSdk 34 (Android 14): READ_MEDIA_AUDIO / POST_NOTIFICATIONS / ACCESS_FINE_LOCATION all required on first launch (OnboardingScreen); service helper needs the model loaded before any separation