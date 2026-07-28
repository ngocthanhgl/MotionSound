# MotionSound — AI Stem Separation

## Status
- Replaced DSP/EQ pipeline with htdemucs ONNX via ONNX Runtime NNAPI

## Architecture
- `StemPlayerService` — unified foreground service (mediaPlayback), replaces MusicService + DriveService
- `StemSeparationEngine` — ONNX Runtime, hann-windowed overlap-add, input `mix (1,2,343980)` → output `stems (1,4,2,343980)`
- `StemMixer` — AudioTrack with per-stem `@Volatile` float volumes, direct float PCM
- `AudioDecoder` — MediaExtractor+MediaCodec → 44100 Hz stereo FloatArray (linear interpolation resampling)
- `StemCache` — SHA-256 keyed, 4 `.raw` float32 files per song
- `SensorDriveMapper` — IMU (accel/gyro) + GPS → stem volume mapping (MBUX-inspired)
- Two ViewModels (`DriveViewModel` + `PlayerViewModel`) both bind to same `StemPlayerService`
- Model loaded from `assets/models/htdemucs_fp16weights.onnx` (via LFS)

## Model
- HuggingFace: StemSplitio/htdemucs-onnx → `htdemucs_fp16weights.onnx` (158 MB)
- Stored via Git LFS on `app/src/main/assets/models/*.onnx`
- Not loaded → pass-through fallback (no separation)

## Key Decisions
- Chunk size = 343980 samples (actual model spec), not 348160
- Input tensor name = `mix`, output = `stems` (actual model spec)
- DriveViewModel auto-starts service in `init { startService() }`
- No LaunchedEffect needed in DriveScreen for service start
- Sensor volumes updated on sensor callback thread via `@Volatile` (no 50 Hz loop)
- Overlap-add with hann window + sum normalization

## Relevant Files
- `app/src/main/java/com/motionsound/stem/` — all 8 stem package files
- `app/src/main/java/com/motionsound/drive/DriveViewModel.kt` — service binding, stem state
- `app/src/main/java/com/motionsound/viewmodel/PlayerViewModel.kt` — service binding, player state
- `app/src/main/java/com/motionsound/ui/screens/DriveScreen.kt` — stem sliders + gauge
- `app/src/main/java/com/motionsound/ui/screens/PlayerScreen.kt` — collapsible stem mix panel
- `app/src/main/java/com/motionsound/ui/screens/SettingsScreen.kt` — model status + cache clear
- `app/src/main/assets/models/` — ONNX model location

## Constraints
- NO local Gradle builds — CI only; fix via CI logs
- NO x86/x86_64 native libs — ONNX Runtime NNAPI requires arm64 device
- APK ≈ 220 MB (158 MB model + libs)
