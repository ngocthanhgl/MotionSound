package com.motionsound.drive

data class EqState(
    val bandGains: FloatArray,
    val volumeReductionDb: Float,
    val lowpassDepth: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EqState) return false
        return bandGains.contentEquals(other.bandGains) &&
                volumeReductionDb == other.volumeReductionDb &&
                lowpassDepth == other.lowpassDepth
    }

    override fun hashCode(): Int {
        return bandGains.contentHashCode() + volumeReductionDb.hashCode() + lowpassDepth.hashCode()
    }
}

data class DspDebugConfig(
    val bypassAll: Boolean = false,
    val enableEQ: Boolean = true,
    val enableLowPass: Boolean = true,
    val enableVolumeDuck: Boolean = true,
    val enableVolumeRamp: Boolean = true
)

object EqStateStore {
    @Volatile
    var state: EqState = EqState(FloatArray(5), 0f)

    @Volatile
    var debugConfig: DspDebugConfig = DspDebugConfig()
}
