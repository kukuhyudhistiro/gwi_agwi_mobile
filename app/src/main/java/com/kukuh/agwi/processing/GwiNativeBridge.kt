package com.kukuh.agwi.processing

/**
 * JNI bridge to native C++ GWi+ODPS and AGWi processing.
 *
 * Usage: When profiling shows Kotlin pipeline bottleneck on older
 * devices, replace calls in GwiEngine with these native methods.
 *
 * The native library is optional. If NDK is not configured,
 * isAvailable() returns false and the app uses the Kotlin pipeline.
 */
object GwiNativeBridge {
    private var loaded = false
    init {
        try { System.loadLibrary("gwi_odps_native"); loaded = true }
        catch (_: UnsatisfiedLinkError) { loaded = false }
    }
    fun isAvailable() = loaded

    @JvmStatic external fun nativeGwi(grayAddr: Long, outAddr: Long)
    @JvmStatic external fun nativeGwiOdps(grayAddr: Long, outAddr: Long)
    @JvmStatic external fun nativeAGwi(grayAddr: Long, outAddr: Long)
    @JvmStatic external fun nativeEntropy(matAddr: Long): Double
}
