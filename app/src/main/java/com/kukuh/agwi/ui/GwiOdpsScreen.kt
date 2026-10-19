package com.kukuh.agwi.ui

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.kukuh.agwi.processing.AlgorithmResult
import com.kukuh.agwi.processing.FrameMetrics
import com.kukuh.agwi.processing.FrameResult
import com.kukuh.agwi.processing.GwiFrameAnalyzer
import com.kukuh.agwi.util.GallerySaver
import java.util.concurrent.Executors
import androidx.compose.ui.tooling.preview.Preview as ComposePreview

/**
 * Single-screen layout:
 *   Camera preview (top 42%) + 2x2 processing grid (50%) + Save button (8%).
 *
 * Grid boxes:
 *   [Canny]     [GWi]
 *   [GWi+ODPS]  [AGWi]
 *
 * Metrics overlay per box (semi-transparent, small font):
 *   Line 1: Algorithm label (green)
 *   Line 2: H=entropy  u=mean  s=std (white)
 *   Line 3: latency_ms  fps (yellow)
 */
@Composable
fun GwiOdpsScreen() {
    val context = LocalContext.current
    var frameResult by remember { mutableStateOf<FrameResult?>(null) }

    val analyzer = remember {
        GwiFrameAnalyzer { result -> frameResult = result }
    }
    DisposableEffect(Unit) { onDispose { analyzer.release() } }

    GwiOdpsContent(
        frameResult = frameResult,
        onCapture = {
            frameResult?.let { r ->
                val composite = analyzer.captureComposite(r)
                GallerySaver.saveToGallery(context, composite)
            }
        },
        cameraPreview = { modifier ->
            CameraPreview(analyzer, modifier)
        }
    )
}

@Composable
fun GwiOdpsContent(
    frameResult: FrameResult?,
    onCapture: () -> Unit,
    cameraPreview: @Composable (Modifier) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        // ── Camera preview ──
        Box(modifier = Modifier.fillMaxWidth().weight(0.42f)) {
            cameraPreview(Modifier.fillMaxSize())
            Text("GWi / AGWi Live - Kukuh", color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp))
        }

        // ── 2x2 Grid ──
        Column(modifier = Modifier.fillMaxWidth().weight(0.50f)) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ResultBox(frameResult?.canny,   "Canny",    Modifier.weight(1f).fillMaxHeight())
                ResultBox(frameResult?.gwi,     "GWi",      Modifier.weight(1f).fillMaxHeight())
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ResultBox(frameResult?.gwiOdps, "GWi+ODPS", Modifier.weight(1f).fillMaxHeight())
                ResultBox(frameResult?.agwi,    "AGWi",     Modifier.weight(1f).fillMaxHeight())
            }
        }

        // ── Save button ──
        Box(modifier = Modifier.fillMaxWidth().weight(0.08f)
            .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center) {
            Button(onClick = onCapture, enabled = frameResult != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))) {
                Text("Capture & Save", color = Color.White, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ResultBox(result: AlgorithmResult?, fallback: String, modifier: Modifier) {
    Box(modifier = modifier.padding(1.dp).background(Color.DarkGray)) {
        if (result != null) {
            Image(bitmap = result.bitmap.asImageBitmap(),
                contentDescription = result.label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit)
            // Metrics overlay
            Column(modifier = Modifier.align(Alignment.TopStart).padding(3.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp)) {
                val m = result.metrics
                Text(result.label, color = Color(0xFF4CAF50), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("H=%.2f u=%.1f s=%.1f".format(m.entropy, m.mean, m.stdDev),
                    color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Text("%dms | %.0ffps".format(m.latencyMs, m.fps),
                    color = Color(0xFFFFEB3B), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        } else {
            Text(fallback, color = Color.Gray, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
fun CameraPreview(analyzer: GwiFrameAnalyzer, modifier: Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(factory = { ctx ->
        val previewView = PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        val cameraFuture = ProcessCameraProvider.getInstance(ctx)
        cameraFuture.addListener({
            val provider = cameraFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480))
                .build().also {
                    it.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer)
                }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(ctx))
        previewView
    }, modifier = modifier)
}

private fun createSampleAlgorithmResult(label: String): AlgorithmResult {
    return AlgorithmResult(
        bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888),
        label = label,
        metrics = FrameMetrics(
            entropy = 4.5,
            mean = 128.0,
            stdDev = 50.0,
            latencyMs = 15,
            fps = 30.0
        )
    )
}

private val sampleFrameResult = FrameResult(
    canny = createSampleAlgorithmResult("Canny"),
    gwi = createSampleAlgorithmResult("GWi"),
    gwiOdps = createSampleAlgorithmResult("GWi+ODPS"),
    agwi = createSampleAlgorithmResult("AGWi")
)

@ComposePreview(showBackground = true)
@Composable
fun GwiOdpsScreenPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            GwiOdpsContent(
                frameResult = sampleFrameResult,
                onCapture = {},
                cameraPreview = { modifier ->
                    Box(modifier.background(Color.DarkGray), contentAlignment = Alignment.Center) {
                        Text("Camera Preview Placeholder", color = Color.White,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }
    }
}
