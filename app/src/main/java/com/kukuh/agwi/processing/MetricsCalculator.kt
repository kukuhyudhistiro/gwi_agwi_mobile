package com.kukuh.agwi.processing

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.log2

/**
 * Holds computed metrics for a single algorithm's output.
 */
data class FrameMetrics(
    val entropy: Double,
    val mean: Double,
    val stdDev: Double,
    val latencyMs: Long,
    val fps: Double
)

/**
 * Efficient metric computation using OpenCV native operations.
 *
 * Shannon Entropy: H = -sum(p * log2(p)) via Imgproc.calcHist
 * Mean + StdDev:   Core.meanStdDev (single native call, very fast)
 */
object MetricsCalculator {

    // Pre-allocated histogram parameters (reused across calls)
    private val histChannels = MatOfInt(0)
    private val histSize = MatOfInt(256)
    private val histRanges = MatOfFloat(0f, 256f)
    private val emptyMask = Mat()

    /**
     * Computes Shannon Entropy of a CV_8UC1 image.
     * Uses OpenCV calcHist internally for speed.
     */
    fun shannonEntropy(gray: Mat): Double {
        val hist = Mat()
        Imgproc.calcHist(
            listOf(gray), histChannels, emptyMask,
            hist, histSize, histRanges
        )
        val total = gray.rows() * gray.cols()
        if (total == 0) { hist.release(); return 0.0 }

        var entropy = 0.0
        val data = FloatArray(256)
        hist.get(0, 0, data)
        for (i in 0 until 256) {
            if (data[i] > 0) {
                val p = data[i] / total.toDouble()
                entropy -= p * log2(p)
            }
        }
        hist.release()
        return entropy
    }

    /**
     * Computes mean and std dev in a single native call.
     * Much faster than computing them separately.
     *
     * @param gray      Input CV_8UC1 Mat
     * @param meanOut   Pre-allocated MatOfDouble for mean
     * @param stdOut    Pre-allocated MatOfDouble for std dev
     */
    fun meanStdDev(gray: Mat, meanOut: MatOfDouble, stdOut: MatOfDouble) {
        Core.meanStdDev(gray, meanOut, stdOut)
    }

    /**
     * Computes all metrics for a single algorithm output.
     * Uses pre-allocated MatOfDouble buffers from MatPool.
     */
    fun computeAll(
        output: Mat,
        latencyMs: Long,
        meanBuf: MatOfDouble,
        stdBuf: MatOfDouble
    ): FrameMetrics {
        val entropy = shannonEntropy(output)
        Core.meanStdDev(output, meanBuf, stdBuf)
        val mean = meanBuf.get(0, 0)[0]
        val std = stdBuf.get(0, 0)[0]
        val fps = if (latencyMs > 0) 1000.0 / latencyMs else 999.0
        return FrameMetrics(entropy, mean, std, latencyMs, fps)
    }
}
