package com.motionsound.drive

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class HeadingFusion {

    private var theta = 0.0
    private var lastGpsFixTime = 0L

    var corneringActive = false
        private set
    var justExitedCorner = false
        private set
    var headingConfidence = 1.0f
        private set
    private var lastCornerEndTimeNanos = 0L

    fun update(omegaZWorld: Float, dt: Float) {
        theta += omegaZWorld.toDouble() * dt.toDouble()
        val decay = (dt / 10f).coerceIn(0f, 0.05f)
        headingConfidence = (headingConfidence * (1f - decay)).coerceAtLeast(0.1f)
    }

    fun onGpsFix(speed: Float, course: Float, accuracy: Float) {
        if (speed < DrivingConfig.MIN_SPEED_FOR_COURSE_MPS) return
        if (accuracy > 50f) return

        lastGpsFixTime = System.nanoTime()

        val thetaGps = (course * PI / 180.0)
        var error = thetaGps - theta
        while (error > PI) error -= 2.0 * PI
        while (error < -PI) error += 2.0 * PI

        val k = when {
            justExitedCorner -> {
                justExitedCorner = false
                DrivingConfig.K_FAST
            }
            corneringActive -> DrivingConfig.K_DURING_TURN
            else -> DrivingConfig.K_NORMAL
        }

        theta += k * error
        headingConfidence = 1.0f
    }

    fun setCorneringState(active: Boolean) {
        if (corneringActive && !active) {
            justExitedCorner = true
            lastCornerEndTimeNanos = System.nanoTime()
        }
        corneringActive = active
    }

    fun getHeading(): Float = theta.toFloat()

    fun wrapAngle(angleRad: Float): Float {
        var a = angleRad
        while (a > PI) a -= (2.0 * PI).toFloat()
        while (a < -PI.toFloat()) a += (2.0 * PI).toFloat()
        return a
    }
}
