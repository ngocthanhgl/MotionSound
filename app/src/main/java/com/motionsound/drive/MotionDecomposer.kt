package com.motionsound.drive

data class MotionFrame(
    val aLong: Float,
    val aLat: Float,
    val aVert: Float,
    val timestamp: Long
)

class MotionDecomposer {

    /**
     * Decomposes world-frame acceleration into vehicle-relative components.
     *
     * Convention:
     *   heading θ measured clockwise from true north.
     *   forward_hat = (sin θ, cos θ)  in East/North components.
     *   right_hat   = (cos θ, -sin θ)
     *
     *   a_long > 0  = accelerating forward
     *   a_long < 0  = braking/decelerating
     *   a_lat  > 0  = right turn (positive lateral acceleration to the right)
     *   a_lat  < 0  = left turn
     *   a_vert > 0  = upward
     *
     * SIGN CONVENTION VALIDATION (Task 4 step 3):
     *   Perform one real left turn and one real right turn.
     *   Left turn  → a_lat should be negative.
     *   Right turn → a_lat should be positive.
     *   If the sign is inverted, swap forward_hat/right_hat or negate a_lat.
     *   This comment documents the intended convention after empirical validation.
     */
    fun decompose(aWorld: FloatArray, heading: Float): MotionFrame {
        val aE = aWorld[0].toDouble()
        val aN = aWorld[1].toDouble()
        val aU = aWorld[2].toDouble()
        val theta = heading.toDouble()

        val sinT = sin(theta)
        val cosT = cos(theta)

        val forwardHatE = sinT
        val forwardHatN = cosT
        val rightHatE = cosT
        val rightHatN = -sinT

        val aLong = (aE * forwardHatE + aN * forwardHatN).toFloat()
        val aLat = (aE * rightHatE + aN * rightHatN).toFloat()
        val aVert = aU

        return MotionFrame(
            aLong = aLong,
            aLat = aLat,
            aVert = aVert,
            timestamp = System.nanoTime()
        )
    }
}
