package com.motionsound.drive

object DrivingConfig {
    const val SENSOR_RATE_HZ = 50
    const val GPS_INTERVAL_MS = 1000L

    const val MADGWICK_BETA = 0.1f

    const val MIN_SPEED_FOR_COURSE_MPS = 2.2f
    const val K_FAST = 0.8f
    const val K_DURING_TURN = 0.05f
    const val K_NORMAL = 0.15f
    const val YAW_THRESH_DEG_PER_S = 9.0f
    const val LAT_THRESH_MPS2 = 2.0f
    const val CORNER_EXIT_DEBOUNCE_S = 0.6f

    const val LPF_CUTOFF_HZ_CAR = 4.0f
    const val LPF_CUTOFF_HZ_MOTORCYCLE = 2.5f
    const val BUMP_HOLD_MS = 250L
    const val BUMP_ADAPTIVE_WINDOW_S = 1.0f

    const val ACCEL_FULL_SCALE_MPS2 = 3.0f
    const val BRAKE_FULL_SCALE_MPS2 = 3.0f
    const val CORNER_FULL_SCALE_MPS2 = 4.0f

    const val IDLE_V_ENTER = 1.0f
    const val IDLE_V_EXIT = 1.5f
    const val IDLE_A_LONG_ENTER = 0.5f
    const val IDLE_A_LAT_ENTER = 0.5f
    const val IDLE_HOLD_MS = 1000L
    const val SLOW_V_LOW = 1.0f
    const val SLOW_V_HIGH = 5.5f
    const val ACCEL_A_ENTER = 1.2f
    const val ACCEL_A_EXIT = 0.6f
    const val ACCEL_HOLD_MS = 300L
    const val CRUISE_A_MAX = 0.5f
    const val CRUISE_V_MIN = 5.5f
    const val CRUISE_HOLD_MS = 1500L
    const val CRUISE_A_EXIT = 0.9f
    const val DECEL_A_ENTER = -1.2f
    const val DECEL_A_EXIT = -0.6f
    const val DECEL_HOLD_MS = 200L
    const val CORNER_LAT_ENTER = 1.5f
    const val CORNER_YAW_ENTER_DPS = 10.0f
    const val CORNER_HOLD_MS = 200L
    const val CORNER_EXIT_MS = 400L

    const val EQ_BAND_COUNT = 5
    const val SAFETY_CEILING_DB = -1.0f
    const val MAX_BOOST_DB = 6.0f
    const val MAX_CUT_DB = -3.0f

    const val ATTACK_TIME_MS = 150f
    const val RELEASE_TIME_MS = 300f

    const val DEFAULT_MAX_SPEED_KMH = 140
}
