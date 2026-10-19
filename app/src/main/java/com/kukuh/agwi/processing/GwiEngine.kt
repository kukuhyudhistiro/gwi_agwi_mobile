package com.kukuh.agwi.processing

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * Unified processing engine for three GWi-family algorithms.
 * Redirects to Native C++ via GwiNativeBridge for Paper 2 performance.
 * Includes Kotlin fallback for when native library is unavailable.
 */
class GwiEngine {

    companion object {
        const val KSIZE       = 5       // Changed to 5 to match snippet/paper
        const val WAVELENGTH  = 4.0
        const val SIGMA       = 2.24    // 0.56 * lambda
        const val GAMMA       = 0.5     // Aspect ratio
        const val PSI         = 0.0     // Phase offset
        const val NUM_ORIENT  = 8
        const val AGWI_KSIZE  = 7
        const val ODPS_D      = 2
    }

    private val filterBank: Array<Mat> by lazy {
        Array(NUM_ORIENT) { idx ->
            val thetaDeg = idx * 22.5
            buildGwiKernel(KSIZE, WAVELENGTH, thetaDeg, SIGMA, GAMMA, PSI)
        }
    }

    private val orientAngles: DoubleArray = DoubleArray(NUM_ORIENT) { idx ->
        Math.toRadians(idx * 22.5)
    }

    /**
     * Runs GWi pipeline. Uses Native JNI if available, else Kotlin.
     */
    fun processGwi(gray: Mat, pool: MatPool) {
        if (GwiNativeBridge.isAvailable()) {
            GwiNativeBridge.nativeGwi(gray.nativeObjAddr, pool.gwiOutput.nativeObjAddr)
        } else {
            // Stage 1: Preprocessing
            Imgproc.equalizeHist(gray, pool.gwiEqualized)
            pool.gwiEqualized.convertTo(pool.gwiNormalized, CvType.CV_32FC1, 1.0 / 255.0)

            // Stages 2-5: Filter bank
            applyFilterBank(pool.gwiNormalized, pool.gwiEdgeMag, pool.gwiOrientMap, pool.gwiResponse, pool.gwiMagnitude, pool.gwiMask, pool.gwiIdxMat)

            // Convert to 8-bit
            Core.normalize(pool.gwiEdgeMag, pool.gwiOutput, 0.0, 255.0, Core.NORM_MINMAX)
            pool.gwiOutput.convertTo(pool.gwiOutput, CvType.CV_8UC1)
        }
    }

    /**
     * Runs GWi+ODPS pipeline. Uses Native JNI if available, else Kotlin.
     */
    fun processGwiOdps(gray: Mat, pool: MatPool) {
        if (GwiNativeBridge.isAvailable()) {
            GwiNativeBridge.nativeGwiOdps(gray.nativeObjAddr, pool.odpsOutput.nativeObjAddr)
        } else {
            // Stage 1: Preprocessing
            Imgproc.equalizeHist(gray, pool.odpsEqualized)
            pool.odpsEqualized.convertTo(pool.odpsNormalized, CvType.CV_32FC1, 1.0 / 255.0)

            // Stages 2-5: Filter bank
            applyFilterBank(pool.odpsNormalized, pool.odpsEdgeMag, pool.odpsOrientMap, pool.odpsResponse, pool.odpsMagnitude, pool.odpsMask, pool.odpsIdxMat)

            // Stage 6: ODPS
            applyOdps(pool.odpsEdgeMag, pool.odpsOrientMap, pool.odpsResult)

            // Convert to 8-bit
            Core.normalize(pool.odpsResult, pool.odpsOutput, 0.0, 255.0, Core.NORM_MINMAX)
            pool.odpsOutput.convertTo(pool.odpsOutput, CvType.CV_8UC1)
        }
    }

    /**
     * Runs the NEW Paper 2 AGWi pipeline.
     */
    fun processAGwi(gray: Mat, pool: MatPool) {
        if (GwiNativeBridge.isAvailable()) {
            GwiNativeBridge.nativeAGwi(gray.nativeObjAddr, pool.agwiOutput.nativeObjAddr)
        } else {
            // AGWi is very slow in Kotlin (O(N*K^2) per pixel).
            // We provide a basic fallback that performs non-adaptive GWi.
            processGwi(gray, pool)
            pool.gwiOutput.copyTo(pool.agwiOutput)
        }
    }

    /**
     * Runs the GWC (Complex Gabor) pipeline for comparison.
     * Note: This allocates temporary Mats and is not optimized for real-time.
     */
    fun processGwc(gray: Mat, pool: MatPool) {
        // Preprocessing
        Imgproc.equalizeHist(gray, pool.gwiEqualized)
        pool.gwiEqualized.convertTo(pool.gwiNormalized, CvType.CV_32FC1, 1.0 / 255.0)

        val combined = Mat.zeros(gray.size(), CvType.CV_32FC1)
        val respReal = Mat()
        val respImag = Mat()
        val r2 = Mat()
        val i2 = Mat()
        val sum = Mat()
        val mag = Mat()

        for (idx in 0 until NUM_ORIENT) {
            val thetaDeg = idx * 22.5
            val realKernel = buildGwcRealKernel(KSIZE, WAVELENGTH, thetaDeg, SIGMA, GAMMA, PSI)
            val imagKernel = filterBank[idx]

            Imgproc.filter2D(pool.gwiNormalized, respReal, CvType.CV_32FC1, realKernel)
            Imgproc.filter2D(pool.gwiNormalized, respImag, CvType.CV_32FC1, imagKernel)

            Core.multiply(respReal, respReal, r2)
            Core.multiply(respImag, respImag, i2)
            Core.add(r2, i2, sum)
            Core.sqrt(sum, mag)

            Core.max(combined, mag, combined)
            realKernel.release()
        }

        Core.normalize(combined, pool.gwiOutput, 0.0, 255.0, Core.NORM_MINMAX)
        pool.gwiOutput.convertTo(pool.gwiOutput, CvType.CV_8UC1)

        combined.release(); respReal.release(); respImag.release()
        r2.release(); i2.release(); sum.release(); mag.release()
    }

    private fun buildGwiKernel(
        ksize: Int, lambda: Double, thetaDeg: Double,
        sigma: Double, gamma: Double, psi: Double
    ): Mat {
        val thetaRad = Math.toRadians(thetaDeg)
        val half = ksize / 2
        val kernel = Mat(ksize, ksize, CvType.CV_32FC1)

        for (row in 0 until ksize) {
            for (col in 0 until ksize) {
                val x = (col - half).toDouble()
                val y = (row - half).toDouble()
                val xTheta =  x * cos(thetaRad) + y * sin(thetaRad)
                val yTheta = -x * sin(thetaRad) + y * cos(thetaRad)

                // Updated to match snippet's exact Gaussian envelope expression
                val envelope = exp(
                    -0.5 * (xTheta * xTheta + gamma * gamma * yTheta * yTheta)
                    / (sigma * sigma)
                )
                val sinusoidal = sin(2.0 * PI * xTheta / lambda + psi)
                kernel.put(row, col, (envelope * sinusoidal).toFloat().toDouble())
            }
        }
        return kernel
    }

    /** Builds a real (cosine) Gabor kernel for GWC comparison. */
    private fun buildGwcRealKernel(
        ksize: Int, lambda: Double, thetaDeg: Double,
        sigma: Double, gamma: Double, psi: Double
    ): Mat {
        val thetaRad = Math.toRadians(thetaDeg)
        val half = ksize / 2
        val kernel = Mat(ksize, ksize, CvType.CV_32FC1)
        for (row in 0 until ksize) {
            for (col in 0 until ksize) {
                val x = (col - half).toDouble()
                val y = (row - half).toDouble()
                val xT =  x * cos(thetaRad) + y * sin(thetaRad)
                val yT = -x * sin(thetaRad) + y * cos(thetaRad)
                val env = exp(-0.5 * (xT * xT + gamma * gamma * yT * yT) / (sigma * sigma))
                val cosComp = cos(2.0 * PI * xT / lambda + psi)
                kernel.put(row, col, (env * cosComp).toFloat().toDouble())
            }
        }
        return kernel
    }

    private fun applyFilterBank(
        normalized: Mat, edgeMag: Mat, orientMap: Mat,
        response: Mat, magnitude: Mat, mask: Mat, idxMat: Mat
    ) {
        edgeMag.setTo(Scalar.all(0.0))
        orientMap.setTo(Scalar.all(0.0))

        for (idx in 0 until NUM_ORIENT) {
            Imgproc.filter2D(normalized, response, CvType.CV_32FC1, filterBank[idx])
            Core.absdiff(response, Scalar.all(0.0), magnitude)

            Core.compare(magnitude, edgeMag, mask, Core.CMP_GT)
            magnitude.copyTo(edgeMag, mask)

            idxMat.setTo(Scalar(idx.toDouble()))
            idxMat.copyTo(orientMap, mask)
        }
    }

    private fun applyOdps(edgeMag: Mat, orientMap: Mat, result: Mat) {
        val rows = edgeMag.rows()
        val cols = edgeMag.cols()
        result.setTo(Scalar.all(0.0))

        val magData = FloatArray(rows * cols)
        val oriData = FloatArray(rows * cols)
        val resData = FloatArray(rows * cols)

        edgeMag.get(0, 0, magData)
        orientMap.get(0, 0, oriData)

        val d = ODPS_D

        for (y in d until rows - d) {
            for (x in d until cols - d) {
                val pixIdx = y * cols + x
                val center = magData[pixIdx]
                if (center <= 0f) continue

                val oriIdx = oriData[pixIdx].toInt().coerceIn(0, NUM_ORIENT - 1)
                val theta = orientAngles[oriIdx]

                val nx = cos(theta)
                val ny = sin(theta)

                val leftX  = (x - d * nx).roundToInt()
                val leftY  = (y - d * ny).roundToInt()
                val rightX = (x + d * nx).roundToInt()
                val rightY = (y + d * ny).roundToInt()

                if (leftX < 0 || leftX >= cols || leftY < 0 || leftY >= rows) continue
                if (rightX < 0 || rightX >= cols || rightY < 0 || rightY >= rows) continue

                val leftVal  = magData[leftY * cols + leftX]
                val rightVal = magData[rightY * cols + rightX]

                if (center >= leftVal && center >= rightVal) {
                    resData[pixIdx] = center
                }
            }
        }
        result.put(0, 0, resData)
    }

    fun release() {
        if (GwiNativeBridge.isAvailable()) {
            // Managed in C++
        } else {
            filterBank.forEach { it.release() }
        }
    }
}
