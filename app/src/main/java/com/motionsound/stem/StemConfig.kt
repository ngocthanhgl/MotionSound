package com.motionsound.stem

object StemConfig {
    const val SAMPLE_RATE = 44100
    const val NUM_STEMS = 4
    const val NUM_CHANNELS = 2
    const val STEM_DRUMS = 0
    const val STEM_BASS = 1
    const val STEM_OTHER = 2
    const val STEM_VOCALS = 3
    val STEM_NAMES = arrayOf("drums", "bass", "other", "vocals")
    val DEFAULT_VOLUMES = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
    const val CACHE_DIR = "stems_cache"
    const val HOP_LENGTH = 1024

    data class StemModelConfig(
        val assetPath: String,
        val nFft: Int,
        val dimF: Int,
        val dimT: Int
    ) {
        val nBins: Int get() = nFft / 2 + 1
        val chunkSize: Int get() = HOP_LENGTH * (dimT - 1)
        val freqPadSize: Int get() = nBins - dimF
    }

    val DRUMS = StemModelConfig("models/kuielab_a_drums_fp32.tflite", nFft = 4096, dimF = 2048, dimT = 512)
    val BASS = StemModelConfig("models/kuielab_a_bass_fp32.tflite", nFft = 16384, dimF = 2048, dimT = 512)
    val OTHER = StemModelConfig("models/kuielab_a_other_fp32.tflite", nFft = 8192, dimF = 2048, dimT = 512)
    val VOCALS = StemModelConfig("models/uvr_mdx_voc_ft_fp32.tflite", nFft = 6144, dimF = 3072, dimT = 256)

    val ALL_MODELS = arrayOf(DRUMS, BASS, OTHER, VOCALS)
}
