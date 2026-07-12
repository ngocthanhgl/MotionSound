package com.motionsound.drive

import kotlin.math.abs

data class ClassifierOutput(
    val state: DrivingState,
    val accelIntensity: Float,
    val brakeIntensity: Float,
    val cornerIntensity: Float,
    val confidence: Float
)

class DrivingClassifier {

    private var currentState = DrivingState.IDLE
    private var stateSinceNanos = System.nanoTime()

    private var accelSinceNanos = 0L
    private var decelSinceNanos = 0L
    private var cornerSinceNanos = 0L
    private var idleSinceNanos = System.nanoTime()
    private var cruiseSinceNanos = 0L

    fun update(filtered: FilteredMotionFrame, speed: Float, gyroZDegPerS: Float, confidenceIn: Float): ClassifierOutput {
        val aLong = filtered.aLongFilt
        val aLat = filtered.aLatFilt
        val now = System.nanoTime()

        val accelIntensity = if (aLong > 0f) (aLong / DrivingConfig.ACCEL_FULL_SCALE_MPS2).coerceIn(0f, 1f) else 0f
        val brakeIntensity = if (aLong < 0f) (-aLong / DrivingConfig.BRAKE_FULL_SCALE_MPS2).coerceIn(0f, 1f) else 0f
        val cornerIntensity = (abs(aLat) / DrivingConfig.CORNER_FULL_SCALE_MPS2).coerceIn(0f, 1f)

        val absALong = abs(aLong)
        val absALat = abs(aLat)
        val speedMs = speed

        val canCorner = absALat > DrivingConfig.CORNER_LAT_ENTER || abs(gyroZDegPerS) > DrivingConfig.CORNER_YAW_ENTER_DPS
        val belowCorner = absALat < DrivingConfig.CORNER_LAT_ENTER * 0.6f && abs(gyroZDegPerS) < DrivingConfig.CORNER_YAW_ENTER_DPS * 0.6f

        val nextState: DrivingState
        val confidence = (confidenceIn * filtered.confidence).coerceIn(0f, 1f)

        when (currentState) {
            DrivingState.IDLE -> {
                if (speedMs > DrivingConfig.IDLE_V_EXIT) {
                    nextState = DrivingState.SLOW_MANEUVERING
                } else {
                    nextState = DrivingState.IDLE
                }
            }

            DrivingState.SLOW_MANEUVERING -> {
                when {
                    canCorner && elapsedMs(cornerSinceNanos, now) >= DrivingConfig.CORNER_HOLD_MS -> nextState = DrivingState.CORNERING
                    aLong > DrivingConfig.ACCEL_A_ENTER && elapsedMs(accelSinceNanos, now) >= DrivingConfig.ACCEL_HOLD_MS -> nextState = DrivingState.ACCELERATING
                    aLong < DrivingConfig.DECEL_A_ENTER && elapsedMs(decelSinceNanos, now) >= DrivingConfig.DECEL_HOLD_MS -> nextState = DrivingState.DECELERATING
                    speedMs < DrivingConfig.SLOW_V_LOW && absALong < DrivingConfig.IDLE_A_LONG_ENTER && absALat < DrivingConfig.IDLE_A_LAT_ENTER && elapsedMs(idleSinceNanos, now) >= DrivingConfig.IDLE_HOLD_MS -> nextState = DrivingState.IDLE
                    speedMs >= DrivingConfig.CRUISE_V_MIN && absALong < DrivingConfig.CRUISE_A_MAX && elapsedMs(cruiseSinceNanos, now) >= DrivingConfig.CRUISE_HOLD_MS -> nextState = DrivingState.CRUISING
                    else -> nextState = DrivingState.SLOW_MANEUVERING
                }
            }

            DrivingState.ACCELERATING -> {
                when {
                    aLong < DrivingConfig.ACCEL_A_EXIT -> nextState = DrivingState.SLOW_MANEUVERING
                    canCorner && elapsedMs(cornerSinceNanos, now) >= DrivingConfig.CORNER_HOLD_MS -> nextState = DrivingState.CORNERING
                    aLong < DrivingConfig.DECEL_A_ENTER && elapsedMs(decelSinceNanos, now) >= DrivingConfig.DECEL_HOLD_MS -> nextState = DrivingState.DECELERATING
                    else -> nextState = DrivingState.ACCELERATING
                }
            }

            DrivingState.CRUISING -> {
                when {
                    absALong > DrivingConfig.CRUISE_A_EXIT -> nextState = DrivingState.SLOW_MANEUVERING
                    canCorner && elapsedMs(cornerSinceNanos, now) >= DrivingConfig.CORNER_HOLD_MS -> nextState = DrivingState.CORNERING
                    speedMs < DrivingConfig.SLOW_V_LOW && absALong < DrivingConfig.IDLE_A_LONG_ENTER && absALat < DrivingConfig.IDLE_A_LAT_ENTER && elapsedMs(idleSinceNanos, now) >= DrivingConfig.IDLE_HOLD_MS -> nextState = DrivingState.IDLE
                    else -> nextState = DrivingState.CRUISING
                }
            }

            DrivingState.DECELERATING -> {
                when {
                    aLong > DrivingConfig.DECEL_A_EXIT -> nextState = DrivingState.SLOW_MANEUVERING
                    canCorner && elapsedMs(cornerSinceNanos, now) >= DrivingConfig.CORNER_HOLD_MS -> nextState = DrivingState.CORNERING
                    else -> nextState = DrivingState.DECELERATING
                }
            }

            DrivingState.CORNERING -> {
                if (belowCorner && elapsedMs(cornerSinceNanos, now) >= DrivingConfig.CORNER_EXIT_MS) {
                    nextState = DrivingState.SLOW_MANEUVERING
                } else {
                    nextState = DrivingState.CORNERING
                }
            }

            else -> nextState = DrivingState.IDLE
        }

        if (nextState != currentState) {
            val transitionNanos = now
            when (nextState) {
                DrivingState.IDLE -> idleSinceNanos = transitionNanos
                DrivingState.ACCELERATING -> accelSinceNanos = transitionNanos
                DrivingState.DECELERATING -> decelSinceNanos = transitionNanos
                DrivingState.CORNERING -> cornerSinceNanos = transitionNanos
                DrivingState.CRUISING -> cruiseSinceNanos = transitionNanos
                else -> {}
            }
            when (currentState) {
                DrivingState.CORNERING -> {}
                DrivingState.ACCELERATING -> {}
                DrivingState.DECELERATING -> {}
                else -> {}
            }
            currentState = nextState
            stateSinceNanos = transitionNanos
        }

        if (nextState == DrivingState.CORNERING && currentState != DrivingState.CORNERING) {
            cornerSinceNanos = now
        }

        return ClassifierOutput(
            state = currentState,
            accelIntensity = accelIntensity,
            brakeIntensity = brakeIntensity,
            cornerIntensity = cornerIntensity,
            confidence = confidence
        )
    }

    private fun elapsedMs(sinceNanos: Long, now: Long): Long = (now - sinceNanos) / 1_000_000L
}
