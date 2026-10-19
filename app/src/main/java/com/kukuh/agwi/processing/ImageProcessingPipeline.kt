package com.kukuh.agwi.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Single algorithm result: bitmap + metrics.
 */
data class AlgorithmResult(
    val bitmap: Bitmap,
    val label: String,
    val metrics: FrameMetrics
)

/**
 * Combined output from all 4 algorithm boxes.
 */
data class FrameResult(
    val canny: AlgorithmResult,
    val gwi: AlgorithmResult,
    val gwiOdps: AlgorithmResult,
    val agwi: AlgorithmResult
)

/**
 * Orchestrates Canny, GWi, GWi+ODPS, and AGWi processing.
 *
 * All Mat operations use pre-allocated buffers from MatPool.
 * No heap allocation inside the per-frame processing path.
 *
 * Processing grid (matches UI 2x2 layout):
 *   Box 1 (top-left):     Canny (baseline, OpenCV defaults 50/150)
 *   Box 2 (top-right):    GWi (Paper 1, Stages 1-5)
 *   Box 3 (bottom-left):  GWi+ODPS (Paper 1, Stages 1-6)
 *   Box 4 (bottom-right): AGWi (Paper 2, density-adaptive sigma)
 */
class ImageProcessingPipeline {

    private val engine = GwiEngine()
    private val pool = MatPool()

    // Pre-allocated bitmaps (reused per frame, recycled on resize)
    private var bmpCanny: Bitmap? = null
    private var bmpGwi: Bitmap? = null
    private var bmpOdps: Bitmap? = null
    private var bmpAgwi: Bitmap? = null
    private var bmpW = 0
    private var bmpH = 0

    /**
     * Processes a single grayscale frame through all 4 algorithms.
     * Zero allocation inside this method (Mats from pool, Bitmaps pre-allocated).
     */
    fun processFrame(gray: Mat): FrameResult {
        val w = gray.cols()
        val h = gray.rows()

        // Ensure pool and bitmaps are allocated for this resolution
        pool.ensureSize(w, h)
        ensureBitmaps(w, h)

        // ── Box 1: Canny ──
        var t0 = System.nanoTime()
        Imgproc.Canny(gray, pool.cannyOut, 50.0, 150.0)
        val cannyMs = (System.nanoTime() - t0) / 1_000_000L
        val cannyMetrics = MetricsCalculator.computeAll(
            pool.cannyOut, cannyMs, pool.meanVal, pool.stdVal
        )
        matToBitmap(pool.cannyOut, bmpCanny!!)

        // ── Box 2: GWi ──
        t0 = System.nanoTime()
        engine.processGwi(gray, pool)
        val gwiMs = (System.nanoTime() - t0) / 1_000_000L
        val gwiMetrics = MetricsCalculator.computeAll(
            pool.gwiOutput, gwiMs, pool.meanVal, pool.stdVal
        )
        matToBitmap(pool.gwiOutput, bmpGwi!!)

        // ── Box 3: GWi+ODPS ──
        t0 = System.nanoTime()
        engine.processGwiOdps(gray, pool)
        val odpsMs = (System.nanoTime() - t0) / 1_000_000L
        val odpsMetrics = MetricsCalculator.computeAll(
            pool.odpsOutput, odpsMs, pool.meanVal, pool.stdVal
        )
        matToBitmap(pool.odpsOutput, bmpOdps!!)

        // ── Box 4: AGWi ──
        t0 = System.nanoTime()
        engine.processAGwi(gray, pool)
        val agwiMs = (System.nanoTime() - t0) / 1_000_000L
        val agwiMetrics = MetricsCalculator.computeAll(
            pool.agwiOutput, agwiMs, pool.meanVal, pool.stdVal
        )
        matToBitmap(pool.agwiOutput, bmpAgwi!!)

        return FrameResult(
            canny   = AlgorithmResult(bmpCanny!!,  "Canny",    cannyMetrics),
            gwi     = AlgorithmResult(bmpGwi!!,    "GWi",      gwiMetrics),
            gwiOdps = AlgorithmResult(bmpOdps!!,   "GWi+ODPS", odpsMetrics),
            agwi    = AlgorithmResult(bmpAgwi!!,   "AGWi",     agwiMetrics)
        )
    }

    /** Convert CV_8UC1 Mat to ARGB_8888 Bitmap using pre-allocated buffer. */
    private fun matToBitmap(gray: Mat, dst: Bitmap) {
        Imgproc.cvtColor(gray, pool.bgraConvert, Imgproc.COLOR_GRAY2BGRA)
        Utils.matToBitmap(pool.bgraConvert, dst)
    }

    /** Ensure Bitmap buffers match current resolution. */
    private fun ensureBitmaps(w: Int, h: Int) {
        if (w == bmpW && h == bmpH) return
        bmpCanny?.recycle(); bmpGwi?.recycle()
        bmpOdps?.recycle(); bmpAgwi?.recycle()
        bmpCanny = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmpGwi   = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmpOdps  = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmpAgwi  = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmpW = w; bmpH = h
    }

    /**
     * Composes 4 result bitmaps into a single 2x2 grid with metric overlays.
     * Used for the "Capture & Save" function.
     */
    fun compositeGrid(result: FrameResult): Bitmap {
        val w = result.canny.bitmap.width
        val h = result.canny.bitmap.height
        val combined = Bitmap.createBitmap(w * 2, h * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        val paint = Paint().apply { isAntiAlias = true }

        val entries = listOf(
            Triple(result.canny,   0f, 0f),
            Triple(result.gwi,     w.toFloat(), 0f),
            Triple(result.gwiOdps, 0f, h.toFloat()),
            Triple(result.agwi,    w.toFloat(), h.toFloat())
        )

        val textPaint = Paint().apply {
            color = Color.WHITE; textSize = 12f * (w / 320f).coerceIn(0.8f, 2f)
            isAntiAlias = true; setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }
        val bgPaint = Paint().apply {
            color = Color.argb(140, 0, 0, 0); style = Paint.Style.FILL
        }

        for ((algo, ox, oy) in entries) {
            canvas.drawBitmap(algo.bitmap, ox, oy, paint)
            val m = algo.metrics
            val line1 = algo.label
            val line2 = "H=%.2f u=%.1f s=%.1f".format(m.entropy, m.mean, m.stdDev)
            val line3 = "%dms %.0ffps".format(m.latencyMs, m.fps)
            val pad = 4f; val lh = textPaint.textSize + 2f
            canvas.drawRect(ox + pad, oy + pad,
                ox + pad + textPaint.measureText(line2) + pad * 2,
                oy + pad + lh * 3 + pad, bgPaint)
            canvas.drawText(line1, ox + pad * 2, oy + pad + lh, textPaint)
            canvas.drawText(line2, ox + pad * 2, oy + pad + lh * 2, textPaint)
            canvas.drawText(line3, ox + pad * 2, oy + pad + lh * 3, textPaint)
        }
        return combined
    }

    fun release() {
        engine.release()
        pool.releaseAll()
        bmpCanny?.recycle(); bmpGwi?.recycle()
        bmpOdps?.recycle(); bmpAgwi?.recycle()
    }
}
