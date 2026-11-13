# MotionSound — AI Stem Separation Music Player

## Status
- Android/Kotlin app (compileSdk 35, minSdk 34, Java 17, 100% Jetpack Compose Material 3)
- AI stem separation: Kirexa MDX-Net TFLite via **LiteRT** — 4 per-stem models (drums/bass/other/vocals) with host-side STFT/iSTFT; GPU delegate first (Immortalis/Mali on MediaTek, Adreno on Snapdragon), automatic CPU-kernel fallback
- Foreground service started at app launch from `MotionSoundApp.onCreate`

## Architecture
- `StemPlayerService` — single foreground service (`foregroundServiceType="mediaPlayback|location"`, START_STICKY): decode → separate → cache → play, seek, queue, pre-cache, sensors, Sound Drive. MediaStyle notification + `MediaSessionCompat` (state/metadata mirrored from `_playerState`), wake lock desired-state pattern (acquired while playing/separating, 4h safety timeout), `AudioFocusRequest` (pause on loss, resume after transient-loss ducking), headset plug/unplug → pause with 30s pending-resume TTL. Both `PlayerViewModel` and `DriveViewModel` start + bind it; `MotionSoundApp` starts it at boot of process
- `StemSeparationEngine` — LiteRT (`org.tensorflow.lite.Interpreter`): 4 per-stem TFLite models loaded from `assets/models/` (kuielab drums/bass/other + UVR VocFT vocals); `StftProcessor` does host-side STFT/iSTFT (packed complex FFT: L+iR in one transform; radix-2 + Bluestein for arbitrary n_fft with cached chirp/window tables; iSTFT treats the spectrogram as frequency-major `[f * dimT + t]`). `initialize(gpuPreferred)` tries GPU delegate then CPU fallback. Reports `backendLabel` ("gpu"/"cpu") + `lastChunkMs`. Per-stem STFT params: drums(n_fft=4096,dim_f=2048,dim_t=512), bass(16384,2048,512), other(8192,2048,512), vocals(6144,3072,256), all hop=1024
- `StemSeparationEngine.separate(pcm, output, onProgress)` — streaming: with a `StemOutput` sink (e.g. `StemCache.WriteSession`) each finished chunk is written directly into mmap'd per-stem temp files; peak heap ≈ 1 chunk (~12 s) instead of 4 full-song direct ByteBuffers (~339 MB). `output = null` keeps the legacy in-memory path. Per-chunk reflect-pad is isolated to that chunk (never pass the whole song for chunk 0)
- `StemMixer` — streaming AudioTrack (PCM float 44.1 kHz stereo, 8192-frame mix buffer, audio thread priority): per-stem volume/filter/pan, master LPF + HPF, stereo reverb (6 comb + 4 allpass per channel, damping one-pole, feedback capped ≤0.82), tremolo, beat-synced vocal gate, 512-frame fade in/out, write-mutex + aborted-flag writer loop, prime-then-play, `onTrackEnded` callback (natural end only — stop() never flushes it); `@Volatile` params set from sensor/UI threads. **Fractional playback**: `tempoScale` (0.93–1.07) advances the source position by `framesToWrite * tempoScale` (linear interpolation, AudioTrack output rate unchanged) — Sound Drive-style tempo modulation; `beatPumpDepth` adds an exponential (~120 ms decay) swell to drums+bass at every detected beat (Sound Drive accelerator pump). Beat grid / vocal-gate indices stay in SOURCE frames, so gating and fade remain musical under tempo changes
- `AudioDecoder` — MediaExtractor+MediaCodec (released in finally, drain-stall bailout >500 empty reads) → short → Float (÷32768), mono→stereo upmix, >2ch fold-down, linear-interp resample to 44100 with box-filter anti-alias when downsampling
- `StemAnalyzer` — drums+bass RMS block (2048) beat detection (threshold mean+1.1σ, 22050 min spacing) + 16-block section energy → drives vocal gating in mixer
- `StemCache` — SHA-256 key (16 hex) per URI, 4 `.raw` float32 files per song in `cacheDir/stems_cache`; 2 GB LRU cap (`enforceCap`); corrupt-file load throws with path+size logged. `openWriteSession(uri, frameCount)` returns a `WriteSession` (`StemOutput` impl): `openBuffer()` mmaps `${key}_$name.tmp`, `commit()` verifies size + atomically renames all 4 tmps to `.raw` (deletes target first; partial failure cleans every tmp), `abort()` deletes tmps — separation call sites in `StemPlayerService` wrap `separate()` in try/catch `CancellationException` and abort on failure/cancel to avoid leaking 4×~340 MB tmp files
- `SensorDriveMapper` — IMU (game rotation vector preferred, linear accel, gyro yaw rate, raw accel fallback, barometer) + GPS (speed 1 s, bearing for world-frame alignment; listener is a singleton, re-registration deduped and gated on `gpsMode`) → driving metrics → `SoundDriveProcessor.update()` → `StemMixer` params; UI StateFlow emissions throttled to ≥100 ms (~10 Hz) with immediate emit on driving-state change
- `SoundDriveProcessor` — 3 modes (BALANCED/DYNAMIC/IMMERSIVE) × intensity × sensor profile; knob-based layered envelope per stem (enter/full thresholds, kick delays, build & lapse, hysteresis, regen-retreat, pace scaling), gestures (ACCEL_BURST/BRAKE_HIT/CORNER_PEAK/BUMP_HIT/TUNNEL_ENTRY), idle muffling + idle vocal accent, roughness→tremolo, corner/brake→reverb, corner→pan; **tempo modulation** (`mixer.tempoScale` = lerp(0.93, 1.07, 0.55·speedGate + 0.45·longitudinalBias), attack 1.5 s / release 2.5 s) + **beat pump** (`mixer.beatPumpDepth` ≈ 0.6·longitudinalBias + 0.2·accel); both reset when disabled (constants `TEMPO_MIN/TEMPO_MAX` in companion, not persisted)
- `StemDsp` — `BiquadFilter` (DF1 LPF/HPF, bypass at bounds, coefficient smoothing 0.03), `StemFxChain` (vol+pan smoothing + filter accumulate; `accumulate` reads fractional source frames with linear interpolation, takes `rate` + optional per-frame `env` multiplier), stereo `Reverb`, `Tremolo`, warp with smoothed depth target
- `SoundDriveConfig` — `SoundDriveMode`(3 values, NO CUSTOM), `GestureType`(5), `SensorProfile`(3); NOTE: `paramsForMode()` hardcodes `i = 0.7f` and `effectiveSensorProfile` hardcodes `SensorProfile.DYNAMIC` — SPORTY/RELAXED are currently unreachable; persisted with volumes/loop in DataStore (`motionsound_prefs`)
- UI — `MainScreen`: 3 tabs (Drive/Songs/Settings) + PlayerScreen; `DriveScreen`: idle layout ↔ fullscreen immersive moving layout (auto at >5 km/h enter, <1 km/h sleep, manual toggle); `SongListScreen`: Songs/Playlists tabs, search, pull-to-refresh, playlist detail with shuffle + batch pre-separation, sort/filter cached in `remember`, O(1) id→index map; `SettingsScreen`: app/dev info dialogs, AI model card (backend + ms/chunk live readout), GPU Acceleration toggle (restart-to-apply), stem cache size + clear
- Data: `SongRepository` (MediaStore, ≤1000 songs w/ truncation flag, IS_MUSIC), `PlaylistRepository` (JSON in filesDir, atomic tmp+rename, corrupt-file quarantine, stale pruning), `SoundPrefsStore` + `ThemeManager` (shared `motionsound_prefs` DataStore)

## Model
- 4 per-stem TFLite models from `skytrix/kirexa-mdx-tflite` (HuggingFace), converted fp32 → **fp16 weights + DEQUANTIZE** via `tools/fp16_weights_surgery.py` (fp32 activations, GPU- and CPU-compatible), ~79 MB total:
  - `kuielab_a_drums_fp16.tflite` (14.3 MB) — n_fft=4096, dim_f=2048, dim_t=512
  - `kuielab_a_bass_fp16.tflite` (14.3 MB) — n_fft=16384, dim_f=2048, dim_t=512
  - `kuielab_a_other_fp16.tflite` (14.3 MB) — n_fft=8192, dim_f=2048, dim_t=512
  - `uvr_mdx_voc_ft_fp16.tflite` (32.0 MB) — n_fft=6144, dim_f=3072, dim_t=256
- Host-side STFT/iSTFT via `StftProcessor` (packed complex FFT: L+iR in one transform; radix-2 + Bluestein for arbitrary n_fft with cached chirp/window tables); GPU delegate first then CPU fallback
- Bundled in repo via Git LFS; not usable → playback blocked ("Model not loaded yet"), stems cache still works

## Key Decisions
- MDX-Net architecture: host computes STFT → model processes spectrogram → host computes iSTFT (avoids complex-type TFLite incompatibility)
- Per-stem STFT params tuned per model; all share hop=1024
- GPU delegate is default (toggle in Settings); warmup sanity check guards against broken vendor OpenCL/Vulkan stacks; CPU path keeps 2×4-thread parallel chunking
- No NNAPI (deprecated Android 15); no QNN EP (MediaTek device must work; one TFLite serves both vendors)
- Service-level `StemUiState` broadcast → both ViewModels; manual volumes persisted; backend/chunkMs surfaced in state
- Separation runs in service on `Dispatchers.IO` job; decode on IO, infer on Default with limited parallelism
- No x86 targets — ABI splits arm64-v8a + armeabi-v7a only (no universal APK)
- R8: `proguard-android-optimize.txt`; keeps `org.tensorflow.lite.gpu.**` (+ dontwarn org.tensorflow.lite)
- `versionCode` derived from `versionName` (X*10000+Y*100+Z); CI runs `assembleDebug` compile check on every push to main, release build only on tag push

## Files
- `app/src/main/java/com/motionsound/stem/` — StemPlayerService, StemSeparationEngine, StemMixer, StemAnalysis/Analyzer, StemCache, StemConfig, AudioDecoder, StftProcessor, SensorDriveMapper, StemUiState (10 files)
- `app/src/main/java/com/motionsound/sounddrive/` — SoundDriveConfig, Sound, StemDsp (BiquadFilter/StemFxChain/Reverb/Tremolo) (3 files)
- `app/src/main/java/com/motionsound/drive/` — DriveState (DrivingState/VehiclePreset enums), DriveViewModel
- `app/src/main/java/com/motionsound/viewmodel/PlayerViewModel.kt` — player state, song/queue/playlists, shuffle/loop, position polling
- `app/src/main/java/com/motionsound/ui/screens/` — MainScreen, DriveScreen, SongsListScreen, PlayerScreen, Setting, OnboardingScreen
- `app/src/main/java/com/motionsound/ui/components/` — DriveDashboard (SpeedGauge + badges), DotSlider, PlayerControls, SongItem, PlaylistCard, AddToPlaylistDialog, SettingsCard
- `app/src/main/java/com/motionsound/data/` — SongRepository, PlaylistRepository, SoundPrefsDataStore, ThemeManager
- `tools/` — conversion + verification harness (Python venv `tools/tflite_env`), not shipped: `fp16_weights_surgery.py` (fp16 surgery; needs `tensorflow.lite` schema or the flatc-generated one via `tflite_schema_compat.py`), `tflite_schema_compat.py` (shim aliasing flatc ≥23 module-level builder funcs onto `schema_py_generated`-style classes; `schema_gen/` from `flatc23\flatc.exe --python --gen-object-api -o tools\schema_gen tools\schema.fbs`), `schema.fbs` (TF v2.14), `flatc23\` + `out\` (converted models, already copied to assets)

## Constraints
- NO local Gradle builds — CI only; fix from CI logs
- NO x86/x86_64 — splits arm64-v8a + armeabi-v7a only
- release APKs ≈100 MB (79 MB fp16 models + native libs)
- minSdk 34 (Android 14): READ_MEDIA_AUDIO / POST_NOTIFICATIONS / ACCESS_FINE_LOCATION all required on first launch (OnboardingScreen); service helper needs the model loaded before any separation
