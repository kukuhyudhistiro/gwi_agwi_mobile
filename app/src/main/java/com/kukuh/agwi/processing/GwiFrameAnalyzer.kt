package com.kukuh.agwi.processing

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.*
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX ImageAnalysis.Analyzer with non-blocking frame dropping.
 *
 * Performance architecture:
 *   AtomicBoolean gate prevents queue build-up.
 *   All computation runs on Dispatchers.Default (not Main).
 *   ImageProxy is closed immediately after bitmap extraction.
 *   Result delivered to UI on Main thread via callback.
 *
 * The ImageProcessingPipeline inside uses pre-allocated MatPool
 * so there are zero heap allocations in the processing hot path.
 */
class GwiFrameAnalyzer(
    private val targetWidth: Int = 320,
    private val onResult: (FrameResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val pipeline = ImageProcessingPipeline()
    private val isProcessing = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Pre-allocated Mat for RGBA input (reused per frame)
    private var inputRgba: Mat? = null
    private var inputGray: Mat? = null
    private var resizedGray: Mat? = null
    private var lastW = 0
    private var lastH = 0

    override fun analyze(image: ImageProxy) {
        // Frame-dropping gate
        if (!isProcessing.compareAndSet(false, true)) {
            image.close()
            return
        }

        val bitmap: Bitmap
        try {
            val rotation = image.imageInfo.rotationDegrees
            val rawBitmap = image.toBitmap()
            bitmap = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            } else {
                rawBitmap
            }
        } catch (e: Exception) {
            image.close()
            isProcessing.set(false)
            return
        }
        image.close()

        scope.launch {
            try {
                val w = bitmap.width
                val h = bitmap.height

                // Ensure input Mats are allocated
                if (w != lastW || h != lastH) {
                    inputRgba?.release()
                    inputGray?.release()
                    resizedGray?.release()
                    inputRgba = Mat(h, w, CvType.CV_8UC4)
                    inputGray = Mat(h, w, CvType.CV_8UC1)
                    val scale = if (w > targetWidth) targetWidth.toDouble() / w else 1.0
                    val rw = (w * scale).toInt()
                    val rh = (h * scale).toInt()
                    resizedGray = Mat(rh, rw, CvType.CV_8UC1)
                    lastW = w; lastH = h
                }

                // Convert Bitmap -> Mat -> Gray
                org.opencv.android.Utils.bitmapToMat(bitmap, inputRgba!!)
                Imgproc.cvtColor(inputRgba!!, inputGray!!, Imgproc.COLOR_RGBA2GRAY)

                // Downscale for real-time performance
                if (inputGray!!.cols() > targetWidth) {
                    Imgproc.resize(inputGray!!, resizedGray!!,
                        resizedGray!!.size(), 0.0, 0.0, Imgproc.INTER_AREA)
                } else {
                    inputGray!!.copyTo(resizedGray!!)
                }

                // Process through all 4 algorithms
                val result = pipeline.processFrame(resizedGray!!)

                withContext(Dispatchers.Main) {
                    onResult(result)
                }
            } catch (_: Exception) {
                // Drop frames that fail silently
            } finally {
                isProcessing.set(false)
            }
        }
    }

    fun captureComposite(result: FrameResult): Bitmap {
        return pipeline.compositeGrid(result)
    }

    fun release() {
        scope.cancel()
        pipeline.release()
        inputRgba?.release()
        inputGray?.release()
        resizedGray?.release()
    }
}
