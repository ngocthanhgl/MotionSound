package com.motionsound.drive

data class EqState(
    val bandGains: FloatArray,
    val reverbMix: Float,
    val volumeReductionDb: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EqState) return false
        return bandGains.contentEquals(other.bandGains) &&
                reverbMix == other.reverbMix &&
                volumeReductionDb == other.volumeReductionDb
    }

    override fun hashCode(): Int {
        return bandGains.contentHashCode() + reverbMix.hashCode() + volumeReductionDb.hashCode()
    }
}

data class DspDebugConfig(
    val bypassAll: Boolean = false,
    val enableEQ: Boolean = true,
    val enableLowPass: Boolean = true,
    val enableReverb: Boolean = true,
    val enableVolumeDuck: Boolean = true,
    val enableVolumeRamp: Boolean = true
)

object EqStateStore {
    @Volatile
    var state: EqState = EqState(FloatArray(5), 0f, 0f)

    @Volatile
    var debugConfig: DspDebugConfig = DspDebugConfig()
}
