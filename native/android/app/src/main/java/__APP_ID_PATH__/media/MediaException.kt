package __APP_ID__.media

/**
 * Error with a stable [code]. The web layer maps codes to user-facing messages
 * (js/ui/messages.js); [message] is only for logs/debugging.
 */
class MediaException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause) {
    companion object {
        const val BAD_REQUEST = "BAD_REQUEST"
        const val UNSUPPORTED_AUDIO = "UNSUPPORTED_AUDIO"
        const val BAD_AUDIO = "BAD_AUDIO"
        const val UNSUPPORTED_IMAGE = "UNSUPPORTED_IMAGE"
        const val BAD_IMAGE = "BAD_IMAGE"
        const val IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE"
        const val NO_SPACE = "NO_SPACE"
        const val READ_FAILED = "READ_FAILED"
        const val STORAGE_FAILED = "STORAGE_FAILED"

        // Playback (Phase 3)
        const val AUDIO_MISSING = "AUDIO_MISSING"
        const val AUDIO_UNPLAYABLE = "AUDIO_UNPLAYABLE"
        const val NO_AUDIO = "NO_AUDIO"
        const val PLAYER_FAILED = "PLAYER_FAILED"

        // Analysis (Phase 4)
        const val ANALYSIS_UNSUPPORTED = "ANALYSIS_UNSUPPORTED"
        const val ANALYSIS_FAILED = "ANALYSIS_FAILED"
        const val ANALYSIS_CANCELLED = "ANALYSIS_CANCELLED"
        const val NO_WAVE_DATA = "NO_WAVE_DATA"
    }
}
