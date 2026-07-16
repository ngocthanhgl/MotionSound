package com.motionsound.drive

data class EqState(
    val bandGains: FloatArray,
    val volumeReductionDb: Float,
    val lowpassDepth: Float = 0f,
    val reverbWet: Float = 0f,
    val tremoloDepth: Float = 0f,
    val tremoloRateHz: Float = 6f,
    val stereoPanOffset: Float = 0f,
    val stereoWidth: Float = 1f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EqState) return false
        return bandGains.contentEquals(other.bandGains) &&
                volumeReductionDb == other.volumeReductionDb &&
                lowpassDepth == other.lowpassDepth &&
                reverbWet == other.reverbWet &&
                tremoloDepth == other.tremoloDepth &&
                tremoloRateHz == other.tremoloRateHz &&
                stereoPanOffset == other.stereoPanOffset &&
                stereoWidth == other.stereoWidth
    }

    override fun hashCode(): Int {
        return bandGains.contentHashCode() + volumeReductionDb.hashCode() +
                lowpassDepth.hashCode() + reverbWet.hashCode() + tremoloDepth.hashCode() +
                tremoloRateHz.hashCode() + stereoPanOffset.hashCode() + stereoWidth.hashCode()
    }
}

data class DspDebugConfig(
    val bypassAll: Boolean = false,
    val enableEQ: Boolean = true,
    val enableLowPass: Boolean = true,
    val enableVolumeDuck: Boolean = true,
    val enableVolumeRamp: Boolean = true,
    val enableReverb: Boolean = true,
    val enableTremolo: Boolean = true,
    val enablePanning: Boolean = true,
    val enableStereoWidth: Boolean = true
)

object EqStateStore {
    @Volatile
    var state: EqState = EqState(FloatArray(5), 0f)

    @Volatile
    var debugConfig: DspDebugConfig = DspDebugConfig()
}
