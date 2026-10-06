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
    }
}
