package com.motionsound.drive

object EqStateStore {
    @Volatile
    var bandGains: FloatArray = FloatArray(5)
    @Volatile
    var reverbMix: Float = 0f
    @Volatile
    var volumeReductionDb: Float = 0f
}
