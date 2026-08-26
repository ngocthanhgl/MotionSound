package com.motionsound.stem

object StemConfig {
    const val MODEL_ASSET_PATH = "models/htdemucs_fp16.tflite"
    const val MODEL_MIN_BYTES = 140_000_000L
    const val SAMPLE_RATE = 44100
    const val NUM_STEMS = 4
    const val NUM_CHANNELS = 2
    const val CHUNK_SAMPLES = 343980
    const val OVERLAP_SAMPLES = CHUNK_SAMPLES / 4
    const val HOP_SAMPLES = CHUNK_SAMPLES - OVERLAP_SAMPLES
    const val STEM_DRUMS = 0
    const val STEM_BASS = 1
    const val STEM_OTHER = 2
    const val STEM_VOCALS = 3
    val STEM_NAMES = arrayOf("drums", "bass", "other", "vocals")
    val DEFAULT_VOLUMES = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
    const val CACHE_DIR = "stems_cache"
}
