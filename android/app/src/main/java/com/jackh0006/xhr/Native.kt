package com.jackh0006.xhr

/**
 * JNI bridge to the Go engine. Every method returns a JSON string or plain
 * text. The library is loaded lazily so the UI can still explain a load
 * failure instead of crashing the process at startup.
 */
object Native {
    private val loadError: Throwable? = runCatching { System.loadLibrary("xhr") }.exceptionOrNull()

    fun error(): String? = loadError?.message

    external fun start(config: String): String
    external fun stop()
    external fun stats(): String
    external fun logs(): String
    external fun clearLogs()
    external fun testSni(json: String): String
    external fun version(): String
}
