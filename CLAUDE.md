# MotionSound — AI Stem Separation Music Player

## Status
- Android/Kotlin app (compileSdk 35, minSdk 34, Java 17, 100% Jetpack Compose Material 3)
- AI stem separation: htdemucs FP16-weight TFLite via **LiteRT** — GPU delegate first (Immortalis/Mali on MediaTek, Adreno on Snapdragon), automatic CPU-kernel fallback
- Foreground service started at app launch from `MotionSoundApp.onCreate`

## Architecture
- `StemPlayerService` — single foreground service (`foregroundServiceType="mediaPlayback|location"`, START_STICKY): decode → separate → cache → play, seek, queue, pre-cache, sensors, Sound Drive. MediaStyle notification + `MediaSessionCompat` (state/metadata mirrored from `_playerState`), wake lock desired-state pattern (acquired while playing/separating, 4h safety timeout), `AudioFocusRequest` (pause on loss, resume after transient-loss ducking), headset plug/unplug → pause with 30s pending-resume TTL. Both `PlayerViewModel` and `DriveViewModel` start + bind it; `MotionSoundApp` starts it at boot of process
- `StemSeparationEngine` — LiteRT (`org.tensorflow.lite.Interpreter`): model mmap'd straight from `assets/models/htdemucs_fp16.tflite` via `AssetFileDescriptor` + `FileChannel.map` (fd held open until release; validated ≥ `MODEL_MIN_BYTES`; NO cacheDir copy, NO download flow). `initialize(gpuPreferred)` tries GPU delegate (`CompatibilityList().isDelegateSupportedOnThisPlatform` → `GpuDelegate(bestOptionsForThisDevice)`, interpreter numThreads=1) then runs a zero-chunk warmup with finite/non-silent output check before adopting; falls back to CPU kernels (≥8 cores → 2×4-thread interpreters run through parallel sessions, else 1×N-thread). Chunk inference writes into direct `ByteBuffer`s, bounded by `nativeInflight`; reports `backendLabel` ("gpu"/"cpu") + `lastChunkMs`. hann-window overlap-add via mmap temp files, hann + sum normalization; chunk = 343980 samples, hop = chunk − chunk/4; input `(1,2,343980)` → output `(1,4,2,343980)`; `sep_*` temp sweep at init; throttled mode slows batches while music plays. GPU toggle persisted in `SoundPrefsStore` key `ai_gpu` (default ON, applied on next engine init)
- `StemMixer` — streaming AudioTrack (PCM float 44.1 kHz stereo, 8192-frame mix buffer, audio thread priority): per-stem volume/filter/pan, master LPF + HPF, stereo reverb (6 comb + 4 allpass per channel, damping one-pole, feedback capped ≤0.82), tremolo, beat-synced vocal gate, 512-frame fade in/out, write-mutex + aborted-flag writer loop, prime-then-play, `onTrackEnded` callback (natural end only — stop() never flushes it); `@Volatile` params set from sensor/UI threads
- `AudioDecoder` — MediaExtractor+MediaCodec (released in finally, drain-stall bailout >500 empty reads) → short → Float (÷32768), mono→stereo upmix, >2ch fold-down, linear-interp resample to 44100 with box-filter anti-alias when downsampling
- `StemAnalyzer` — drums+bass RMS block (2048) beat detection (threshold mean+1.1σ, 22050 min spacing) + 16-block section energy → drives vocal gating in mixer
- `StemCache` — SHA-256 key (16 hex) per URI, 4 `.raw` float32 files per song in `cacheDir/stems_cache`; atomic tmp+rename writes; 2 GB LRU cap (`enforceCap`); corrupt-file load throws with path+size logged
- `SensorDriveMapper` — IMU (game rotation vector preferred, linear accel, gyro yaw rate, raw accel fallback, barometer) + GPS (speed 1 s, bearing for world-frame alignment; listener is a singleton, re-registration deduped and gated on `gpsMode`) → driving metrics → `SoundDriveProcessor.update()` → `StemMixer` params; UI StateFlow emissions throttled to ≥100 ms (~10 Hz) with immediate emit on driving-state change
- `SoundDriveProcessor` — 3 modes (BALANCED/DYNAMIC/IMMERSIVE) × intensity × sensor profile; knob-based layered envelope per stem (enter/full thresholds, kick delays, build & lapse, hysteresis, regen-retreat, pace scaling), gestures (ACCEL_BURST/BRAKE_HIT/CORNER_PEAK/BUMP_HIT/TUNNEL_ENTRY), idle muffling + idle vocal accent, roughness→tremolo, corner/brake→reverb, corner→pan; full state reset when disabled
- `StemDsp` — `BiquadFilter` (DF1 LPF/HPF, bypass at bounds, coefficient smoothing 0.03), `StemFxChain` (vol+pan smoothing + filter accumulate), stereo `Reverb`, `Tremolo`, warp with smoothed depth target
- `SoundDriveConfig` — `SoundDriveMode`(3 values, NO CUSTOM), `GestureType`(5), `SensorProfile`(3); NOTE: `paramsForMode()` hardcodes `i = 0.7f` and `effectiveSensorProfile` hardcodes `SensorProfile.DYNAMIC` — SPORTY/RELAXED are currently unreachable; persisted with volumes/loop in DataStore (`motionsound_prefs`)
- UI — `MainScreen`: 3 tabs (Drive/Songs/Settings) + PlayerScreen; `DriveScreen`: idle layout ↔ fullscreen immersive moving layout (auto at >5 km/h enter, <1 km/h sleep, manual toggle); `SongListScreen`: Songs/Playlists tabs, search, pull-to-refresh, playlist detail with shuffle + batch pre-separation, sort/filter cached in `remember`, O(1) id→index map; `SettingsScreen`: app/dev info dialogs, AI model card (backend + ms/chunk live readout), GPU Acceleration toggle (restart-to-apply), stem cache size + clear
- Data: `SongRepository` (MediaStore, ≤1000 songs w/ truncation flag, IS_MUSIC), `PlaylistRepository` (JSON in filesDir, atomic tmp+rename, corrupt-file quarantine, stale pruning), `SoundPrefsStore` + `ThemeManager` (shared `motionsound_prefs` DataStore)

## Model
- `app/src/main/assets/models/htdemucs_fp16.tflite` (~160 MB, fp16 WEIGHTS only — fp32 activations/IO so CPU kernels also work)
- Produced by `tools/convert_htdemucs_tflite.py` pipeline: onnx2tf flatbuffer_direct from the exact shipped ONNX → float32 tflite → `tools/fp16_weights_surgery.py` rewrites constants ≥16 KB into fp16 storage + builtin DEQUANTIZE ops
- Verify gate before shipping: same chunks through ORT x64 (original ONNX) vs TFLite CPU interpreter must agree per-stem cosine ≥ 0.9995 (`tools/verify_report.txt`)
- Bundled in repo via Git LFS (`noCompress "tflite"`); no network download path exists anymore
- Not usable (missing/corrupt/GPU+CPU both fail init) → playback blocked ("Model not loaded yet"), stems cache still works

## Key Decisions
- Chunk = 343980 samples (model spec), hop = chunk − chunk/4, hann window + sum-normalization overlap-add
- GPU delegate is default (toggle in Settings); warmup sanity check guards against broken vendor OpenCL/Vulkan stacks; CPU path keeps 2×4-thread parallel chunking
- No NNAPI (deprecated Android 15); no QNN EP (MediaTek device must work; one TFLite serves both vendors)
- Service-level `StemUiState` broadcast → both ViewModels; manual volumes persisted; backend/chunkMs surfaced in state
- Separation runs in service on `Dispatchers.IO` job; decode on IO, infer on Default with limited parallelism
- No x86 targets — ABI splits arm64-v8a + armeabi-v7a only (no universal APK)
- R8: `proguard-android-optimize.txt`; keeps `org.tensorflow.lite.gpu.**` (+ dontwarn org.tensorflow.lite)
- `versionCode` derived from `versionName` (X*10000+Y*100+Z); CI runs `assembleDebug` compile check on every push to main, release build only on tag push

## Files
- `app/src/main/java/com/motionsound/stem/` — StemPlayerService, StemSeparationEngine, StemMixer, StemAnalysis/Analyzer, StemCache, StemConfig, AudioDecoder, SensorDriveMapper, StemUiState (9 files)
- `app/src/main/java/com/motionsound/sounddrive/` — SoundDriveConfig, Sound, StemDsp (BiquadFilter/StemFxChain/Reverb/Tremolo) (3 files)
- `app/src/main/java/com/motionsound/drive/` — DriveState (DrivingState/VehiclePreset enums), DriveViewModel
- `app/src/main/java/com/motionsound/viewmodel/PlayerViewModel.kt` — player state, song/queue/playlists, shuffle/loop, position polling
- `app/src/main/java/com/motionsound/ui/screens/` — MainScreen, DriveScreen, SongsListScreen, PlayerScreen, Setting, OnboardingScreen
- `app/src/main/java/com/motionsound/ui/components/` — DriveDashboard (SpeedGauge + badges), DotSlider, PlayerControls, SongItem, PlaylistCard, AddToPlaylistDialog, SettingsCard
- `app/src/main/java/com/motionsound/data/` — SongRepository, PlaylistRepository, SoundPrefsDataStore, ThemeManager
- `tools/` — conversion + verification harness (Python venv `tools/tflite_env`), not shipped

## Constraints
- NO local Gradle builds — CI only; fix from CI logs
- NO x86/x86_64 — splits arm64-v8a + armeabi-v7a only
- release APKs ≈180 MB (160 MB model + native libs)
- minSdk 34 (Android 14): READ_MEDIA_AUDIO / POST_NOTIFICATIONS / ACCESS_FINE_LOCATION all required on first launch (OnboardingScreen); service helper needs the model loaded before any separation
