package com.kukuh.agwi.processing

import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Pre-allocated Mat pool for zero-allocation inside the analyze() loop.
 *
 * CRITICAL CONSTRAINT: No `new Mat()` or object instantiation is
 * permitted inside the per-frame processing hot path. All intermediate
 * buffers are allocated once in this pool and reused via setTo(Scalar.all(0))
 * between frames.
 *
 * Pool is sized for a fixed processing resolution. If the camera
 * resolution changes, call resize() to reallocate.
 *
 * Memory layout (per algorithm, 4 total):
 *   equalized   : CV_8UC1   - histogram equalized output
 *   normalized  : CV_32FC1  - [0,1] normalized float
 *   response    : CV_32FC1  - convolution response (reused per orientation)
 *   magnitude   : CV_32FC1  - abs(response)
 *   edgeMag     : CV_32FC1  - cross-orientation max-pool result
 *   orientMap   : CV_32FC1  - argmax orientation index
 *   odpsResult  : CV_32FC1  - ODPS output
 *   output8u    : CV_8UC1   - final 8-bit result
 *   bgraMat     : CV_8UC4   - for matToBitmap conversion
 *   mask        : CV_8UC1   - comparison mask
 *   idxMat      : CV_32FC1  - orientation index fill
 *
 * Shared buffers (allocated once):
 *   grayFrame   : CV_8UC1   - input grayscale (shared across all algorithms)
 *   rgbaMat     : CV_8UC4   - camera frame as Mat
 *   cannyOut    : CV_8UC1   - Canny output (separate, not reusable)
 *
 * For AGWi additional:
 *   densityMap  : CV_32FC1  - local density map
 *   blurred     : CV_8UC1   - blurred for density estimation
 */
class MatPool {

    // Shared input buffers
    lateinit var rgbaMat: Mat; private set
    lateinit var grayFrame: Mat; private set

    // Per-algorithm buffer sets
    lateinit var cannyOut: Mat; private set

    // GWi buffers
    lateinit var gwiEqualized: Mat; private set
    lateinit var gwiNormalized: Mat; private set
    lateinit var gwiResponse: Mat; private set
    lateinit var gwiMagnitude: Mat; private set
    lateinit var gwiEdgeMag: Mat; private set
    lateinit var gwiOrientMap: Mat; private set
    lateinit var gwiOutput: Mat; private set
    lateinit var gwiMask: Mat; private set
    lateinit var gwiIdxMat: Mat; private set

    // GWi+ODPS buffers (shares equalized/normalized with GWi or own set)
    lateinit var odpsEqualized: Mat; private set
    lateinit var odpsNormalized: Mat; private set
    lateinit var odpsResponse: Mat; private set
    lateinit var odpsMagnitude: Mat; private set
    lateinit var odpsEdgeMag: Mat; private set
    lateinit var odpsOrientMap: Mat; private set
    lateinit var odpsResult: Mat; private set
    lateinit var odpsOutput: Mat; private set
    lateinit var odpsMask: Mat; private set
    lateinit var odpsIdxMat: Mat; private set

    // AGWi buffers
    lateinit var agwiNormalized: Mat; private set
    lateinit var agwiResponse: Mat; private set
    lateinit var agwiMagnitude: Mat; private set
    lateinit var agwiEdgeMag: Mat; private set
    lateinit var agwiOrientMap: Mat; private set
    lateinit var agwiOutput: Mat; private set
    lateinit var agwiMask: Mat; private set
    lateinit var agwiIdxMat: Mat; private set
    lateinit var agwiBlurred: Mat; private set
    lateinit var agwiDensity: Mat; private set

    // Conversion buffers (shared across all algorithms for matToBitmap)
    lateinit var bgraConvert: Mat; private set

    // For meanStdDev
    val meanVal = MatOfDouble()
    val stdVal = MatOfDouble()

    private var allocatedW = 0
    private var allocatedH = 0

    /** Allocate or reallocate all buffers for given dimensions. */
    fun ensureSize(width: Int, height: Int) {
        if (width == allocatedW && height == allocatedH) return
        releaseAll()

        val sz = Size(width.toDouble(), height.toDouble())

        rgbaMat = Mat(sz, CvType.CV_8UC4)
        grayFrame = Mat(sz, CvType.CV_8UC1)
        cannyOut = Mat(sz, CvType.CV_8UC1)

        // GWi set
        gwiEqualized = Mat(sz, CvType.CV_8UC1)
        gwiNormalized = Mat(sz, CvType.CV_32FC1)
        gwiResponse = Mat(sz, CvType.CV_32FC1)
        gwiMagnitude = Mat(sz, CvType.CV_32FC1)
        gwiEdgeMag = Mat(sz, CvType.CV_32FC1)
        gwiOrientMap = Mat(sz, CvType.CV_32FC1)
        gwiOutput = Mat(sz, CvType.CV_8UC1)
        gwiMask = Mat(sz, CvType.CV_8UC1)
        gwiIdxMat = Mat(sz, CvType.CV_32FC1)

        // ODPS set
        odpsEqualized = Mat(sz, CvType.CV_8UC1)
        odpsNormalized = Mat(sz, CvType.CV_32FC1)
        odpsResponse = Mat(sz, CvType.CV_32FC1)
        odpsMagnitude = Mat(sz, CvType.CV_32FC1)
        odpsEdgeMag = Mat(sz, CvType.CV_32FC1)
        odpsOrientMap = Mat(sz, CvType.CV_32FC1)
        odpsResult = Mat(sz, CvType.CV_32FC1)
        odpsOutput = Mat(sz, CvType.CV_8UC1)
        odpsMask = Mat(sz, CvType.CV_8UC1)
        odpsIdxMat = Mat(sz, CvType.CV_32FC1)

        // AGWi set
        agwiNormalized = Mat(sz, CvType.CV_32FC1)
        agwiResponse = Mat(sz, CvType.CV_32FC1)
        agwiMagnitude = Mat(sz, CvType.CV_32FC1)
        agwiEdgeMag = Mat(sz, CvType.CV_32FC1)
        agwiOrientMap = Mat(sz, CvType.CV_32FC1)
        agwiOutput = Mat(sz, CvType.CV_8UC1)
        agwiMask = Mat(sz, CvType.CV_8UC1)
        agwiIdxMat = Mat(sz, CvType.CV_32FC1)
        agwiBlurred = Mat(sz, CvType.CV_8UC1)
        agwiDensity = Mat(sz, CvType.CV_32FC1)

        bgraConvert = Mat(sz, CvType.CV_8UC4)

        allocatedW = width
        allocatedH = height
    }

    /** Reset all accumulation buffers to zero before each frame. */
    fun resetAccumulators() {
        gwiEdgeMag.setTo(Scalar.all(0.0))
        gwiOrientMap.setTo(Scalar.all(0.0))
        odpsEdgeMag.setTo(Scalar.all(0.0))
        odpsOrientMap.setTo(Scalar.all(0.0))
        agwiEdgeMag.setTo(Scalar.all(0.0))
        agwiOrientMap.setTo(Scalar.all(0.0))
    }

    fun releaseAll() {
        if (allocatedW == 0) return
        listOf(
            rgbaMat, grayFrame, cannyOut,
            gwiEqualized, gwiNormalized, gwiResponse, gwiMagnitude,
            gwiEdgeMag, gwiOrientMap, gwiOutput, gwiMask, gwiIdxMat,
            odpsEqualized, odpsNormalized, odpsResponse, odpsMagnitude,
            odpsEdgeMag, odpsOrientMap, odpsResult, odpsOutput, odpsMask, odpsIdxMat,
            agwiNormalized, agwiResponse, agwiMagnitude,
            agwiEdgeMag, agwiOrientMap, agwiOutput, agwiMask, agwiIdxMat,
            agwiBlurred, agwiDensity,
            bgraConvert, meanVal, stdVal
        ).forEach { it.release() }
        allocatedW = 0; allocatedH = 0
    }
}
