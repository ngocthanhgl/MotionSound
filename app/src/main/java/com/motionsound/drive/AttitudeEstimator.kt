package com.motionsound.drive

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AttitudeEstimator(private val beta: Float = DrivingConfig.MADGWICK_BETA) {

    private var q0 = 1.0
    private var q1 = 0.0
    private var q2 = 0.0
    private var q3 = 0.0

    fun update(gyro: FloatArray, accel: FloatArray, dt: Float) {
        val ax = accel[0].toDouble(); val ay = accel[1].toDouble(); val az = accel[2].toDouble()
        var gx = gyro[0].toDouble(); var gy = gyro[1].toDouble(); var gz = gyro[2].toDouble()

        if (!ax.isFinite() || !ay.isFinite() || !az.isFinite()) return
        if (!gx.isFinite() || !gy.isFinite() || !gz.isFinite()) return

        val norm = sqrt(ax * ax + ay * ay + az * az)
        if (norm < 1e-6) return
        val nx = ax / norm
        val ny = ay / norm
        val nz = az / norm

        val _2q0 = 2.0 * q0; val _2q1 = 2.0 * q1; val _2q2 = 2.0 * q2; val _2q3 = 2.0 * q3
        val _4q0 = 4.0 * q0; val _4q1 = 4.0 * q1; val _4q2 = 4.0 * q2
        val _8q1 = 8.0 * q1; val _8q2 = 8.0 * q2
        val q0q0 = q0 * q0; val q1q1 = q1 * q1; val q2q2 = q2 * q2; val q3q3 = q3 * q3

        val s0 = _4q0 * q2q2 + _2q2 * nx + _4q0 * q1q1 - _2q1 * ny
        val s1 = _4q1 * q3q3 - _2q3 * nx + 4.0 * q0q0 * q1 - _2q0 * ny - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * nz
        val s2 = 4.0 * q0q0 * q2 + _2q0 * nx + _4q2 * q3q3 - _2q3 * ny - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * nz
        val s3 = 4.0 * q1q1 * q3 - _2q1 * nx + 4.0 * q2q2 * q3 - _2q2 * ny

        val sNorm = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
        if (sNorm < 1e-6 || !sNorm.isFinite()) return
        val s0n = s0 / sNorm; val s1n = s1 / sNorm; val s2n = s2 / sNorm; val s3n = s3 / sNorm

        gx -= (beta * s0n).toFloat()
        gy -= (beta * s1n).toFloat()
        gz -= (beta * s2n).toFloat()

        val dtD = dt.toDouble()
        q0 += 0.5 * (-q1 * gx - q2 * gy - q3 * gz) * dtD
        q1 += 0.5 * (q0 * gx + q2 * gz - q3 * gy) * dtD
        q2 += 0.5 * (q0 * gy - q1 * gz + q3 * gx) * dtD
        q3 += 0.5 * (q0 * gz + q1 * gy - q2 * gx) * dtD

        if (!q0.isFinite() || !q1.isFinite() || !q2.isFinite() || !q3.isFinite()) {
            q0 = 1.0; q1 = 0.0; q2 = 0.0; q3 = 0.0; return
        }

        val qNorm = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        if (qNorm < 1e-6) return
        q0 /= qNorm; q1 /= qNorm; q2 /= qNorm; q3 /= qNorm
    }

    fun getQuaternion(): FloatArray = floatArrayOf(q0.toFloat(), q1.toFloat(), q2.toFloat(), q3.toFloat())

    fun getGravity(): FloatArray {
        if (!q0.isFinite() || !q1.isFinite() || !q2.isFinite() || !q3.isFinite()) {
            return floatArrayOf(0f, 0f, -1f)
        }
        val gx = 2.0 * (q1 * q3 - q0 * q2)
        val gy = 2.0 * (q0 * q1 + q2 * q3)
        val gz = q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3
        return floatArrayOf(gx.toFloat(), gy.toFloat(), gz.toFloat())
    }

    fun getYaw(): Float {
        return atan2(2.0 * (q0 * q3 + q1 * q2), 1.0 - 2.0 * (q2 * q2 + q3 * q3)).toFloat()
    }

    fun getWorldFrameCorrected(linear: FloatArray, headingRad: Float): FloatArray {
        val yawQ = getYaw().toDouble()
        val delta = headingRad.toDouble() - yawQ
        val cosD = cos(delta)
        val sinD = sin(delta)
        val aWorld = getWorldFrame(linear)
        val xe = aWorld[0].toDouble() * cosD - aWorld[1].toDouble() * sinD
        val xn = aWorld[0].toDouble() * sinD + aWorld[1].toDouble() * cosD
        return floatArrayOf(xe.toFloat(), xn.toFloat(), aWorld[2])
    }

    fun getLinearAccel(accelRaw: FloatArray): FloatArray {
        val g = getGravity()
        return floatArrayOf(
            accelRaw[0] - g[0],
            accelRaw[1] - g[1],
            accelRaw[2] - g[2]
        )
    }

    fun getWorldFrame(linear: FloatArray): FloatArray {
        val vx = linear[0].toDouble(); val vy = linear[1].toDouble(); val vz = linear[2].toDouble()
        if (!vx.isFinite() || !vy.isFinite() || !vz.isFinite()) return floatArrayOf(0f, 0f, 0f)
        if (!q0.isFinite() || !q1.isFinite() || !q2.isFinite() || !q3.isFinite()) return floatArrayOf(0f, 0f, 0f)
        val q00 = q0 * q0; val q11 = q1 * q1; val q22 = q2 * q2; val q33 = q3 * q3
        val q01 = q0 * q1; val q02 = q0 * q2; val q03 = q0 * q3
        val q12 = q1 * q2; val q13 = q1 * q3; val q23 = q2 * q3

        val xe = vx * (q00 + q11 - q22 - q33) + 2.0 * vy * (q12 - q03) + 2.0 * vz * (q13 + q02)
        val xn = 2.0 * vx * (q12 + q03) + vy * (q00 - q11 + q22 - q33) + 2.0 * vz * (q23 - q01)
        val xu = 2.0 * vx * (q13 - q02) + 2.0 * vy * (q23 + q01) + vz * (q00 - q11 - q22 + q33)

        return floatArrayOf(xe.toFloat(), xn.toFloat(), xu.toFloat())
    }
}
