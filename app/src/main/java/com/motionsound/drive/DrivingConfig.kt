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
    const val CORNER_INTENSITY_THRESHOLD = 0.15f

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
    const val SAFETY_CEILING_DB = 6.0f
    const val MAX_BOOST_DB = 6.0f
    const val MAX_CUT_DB = -12f

    const val ATTACK_TIME_MS = 120f
    const val RELEASE_TIME_MS = 120f

    const val DEFAULT_MAX_SPEED_KMH = 140
    const val MIN_SPEED_RATIO = 0.02f

    const val IDLE_VOLUME_REDUCTION_DB = -20f
    const val BRAKE_VOLUME_REDUCTION_DB = -14f
    const val CORNER_VOLUME_REDUCTION_DB = -14f
    const val ACCEL_VOLUME_BOOST_DB = 2f
    const val CORNER_SPEED_THRESHOLD_KMH = 40
    const val CORNER_VOL_ATTACK = 0.3f
    const val CORNER_VOL_RELEASE = 0.02f
    const val VOL_SMOOTH_ATTACK = 0.5f
    const val VOL_SMOOTH_RELEASE = 0.2f

    const val REVERB_CORNER_DEPTH = 0.45f
    const val REVERB_BRAKE_DEPTH = 0.3f
    const val REVERB_COMB_FEEDBACK = 0.84f
    const val REVERB_ALLPASS_GAIN = 0.5f

    const val IDLE_BASS_CUT_DB = -12f
    const val IDLE_HIGH_CUT_DB = -12f

    const val VOCAL_LOW_HZ = 300
    const val VOCAL_HIGH_HZ = 3000
    const val VOCAL_OUTSIDE_CUT_DB = -8f
    const val VOCAL_INSIDE_BOOST_DB = 4f
    const val IDLE_REVERB_DEPTH = 0.35f

    const val ACCEL_REVERB_DEPTH = 0.3f
    const val ACCEL_TREBLE_BOOST_DB = 4f

    const val CORNER_MID_CUT_DB = -4f
    const val CORNER_BASS_BOOST_DB = 4f
    const val CORNER_PAN_DEPTH = 0.5f

    const val REGEN_ALONG_THRESHOLD = -0.5f
    const val REGEN_VOLUME_REDUCTION_DB = -4f
    const val REGEN_REVERB_DEPTH = 0.1f

    const val SPEED_STEREO_WIDTH_MAX = 0.6f
}
