package com.motionsound.drive

data class EqState(
    val bandGains: FloatArray,
    val volumeReductionDb: Float,
    val reverbWet: Float = 0f,
    val stereoPanOffset: Float = 0f,
    val stereoWidth: Float = 1f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EqState) return false
        return bandGains.contentEquals(other.bandGains) &&
                volumeReductionDb == other.volumeReductionDb &&
                reverbWet == other.reverbWet &&
                stereoPanOffset == other.stereoPanOffset &&
                stereoWidth == other.stereoWidth
    }

    override fun hashCode(): Int {
        return bandGains.contentHashCode() + volumeReductionDb.hashCode() +
                reverbWet.hashCode() + stereoPanOffset.hashCode() + stereoWidth.hashCode()
    }
}

data class DspDebugConfig(
    val bypassAll: Boolean = false,
    val enableEQ: Boolean = true,
    val enableVolumeDuck: Boolean = true,
    val enableVolumeRamp: Boolean = true,
    val enableReverb: Boolean = true,
    val enablePanning: Boolean = true,
    val enableStereoWidth: Boolean = true
)

object EqStateStore {
    @Volatile
    var state: EqState = EqState(FloatArray(5), 0f)

    @Volatile
    var debugConfig: DspDebugConfig = DspDebugConfig()
}
