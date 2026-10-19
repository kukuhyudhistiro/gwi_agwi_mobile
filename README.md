# GWi+AGWi Android: Real-Time Edge Detection Comparison

Android app comparing four edge detection algorithms in real-time from camera stream.

**Papers:**
- Paper 1: "Imaginary-Only Gabor Wavelet with ODPS" (GWi+ODPS, Yudhistiro 2026, DSP Elsevier)
- Paper 2: "Adaptive-GWi for Corn Kernel Detection" (AGWi, Yudhistiro 2026, JESA)


## Screen Layout

```
+-----------------------------------+
|     CameraX Live Preview          |  42%
+-----------------------------------+
| [Canny]          | [GWi]          |
|  H=x u=x s=x    |  H=x u=x s=x  |  50% (2x2 grid)
|  Nms  Xfps       |  Nms  Xfps     |
+-----------------+-----------------+
| [GWi+ODPS]       | [AGWi]         |
|  H=x u=x s=x    |  H=x u=x s=x  |
|  Nms  Xfps       |  Nms  Xfps     |
+-----------------+-----------------+
|        [ Capture & Save ]         |  8%
+-----------------------------------+
```

Metrics per box: Shannon Entropy (H), Mean (u), Std Dev (s), Latency (ms), FPS.


## Algorithm Comparison

| Algorithm | Paper | Convolutions | Histogram EQ | Sigma | Post-processing |
|-----------|-------|-------------|-------------|-------|-----------------|
| Canny     | -     | gradient    | no          | fixed | NMS + hysteresis|
| GWi       | 1     | N=8         | yes         | 2.24  | none            |
| GWi+ODPS  | 1     | N=8         | yes         | 2.24  | ODPS (Stage 6)  |
| AGWi      | 2     | 3*N=24 max  | **no**      | 1.5/2.5/3.5 | none      |

Key differences between GWi and AGWi:
- GWi uses fixed sigma=2.24 for all pixels
- AGWi adapts sigma per local region based on edge density (rho_L)
- AGWi omits histogram equalization to isolate the adaptive mechanism
- AGWi uses 3 discrete sigma levels (scalar quantization of density)
- AGWi uses fixed 7x7 kernel (adaptive kernel size was removed from Paper 2)


## Pre-Allocation Strategy (Zero-Allocation Hot Path)

All intermediate Mat objects are pre-allocated in MatPool.kt and reused per frame.
No `new Mat()` or object instantiation inside the `analyze()` callback.

```
MatPool
  |-- Shared: rgbaMat, grayFrame, bgraConvert
  |-- Canny:  cannyOut
  |-- GWi:    equalized, normalized, response, magnitude,
  |           edgeMag, orientMap, output, mask, idxMat
  |-- ODPS:   (same set as GWi, independent buffers)
  |-- AGWi:   normalized, response, magnitude,
              edgeMag, orientMap, output, mask, idxMat,
              blurred, density
  |-- Metrics: meanVal, stdVal (MatOfDouble, reused)
```

Between frames: accumulator Mats reset via `setTo(Scalar.all(0))`.
Bitmaps: pre-allocated per resolution, reused via `Utils.matToBitmap()`.


## Python-to-Kotlin Mapping

### Paper 1 (GWi+ODPS)

```
Python (gwi-odps/scripts/)            Kotlin (GwiEngine.kt)
------------------------------------  ------------------------------------
gabor_kernel_imaginary()              buildGwiKernel()
create_gwi_filter_bank_8orient()      gwiFilterBank (init block)
apply_gwi_filter_bank()               runFilterBank()
ndimage.convolve / cv2.filter2D       Imgproc.filter2D()
np.abs(response)                      Core.absdiff()
np.max(stack, axis=0)                 Core.compare + copyTo(mask)
ODPS pseudocode Alg.1 ln 16-26       applyOdps()
```

### Paper 2 (AGWi)

```
Python (agwi/scripts/)                Kotlin (GwiEngine.kt)
------------------------------------  ------------------------------------
compute_local_density()               computeLocalDensity()
adapt_sigma(rho_L)                    agwiSigmaLevels[] + density thresholds
agwi_pipeline()                       processAGwi()
  - no histogram equalization           gray.convertTo(norm, ...) directly
  - 3 sigma levels (SQ)                agwiFilterBanks[3][8]
  - composite by density mask           copyTo(finalEdge, mask)
```

AGWi scalar quantization:
```
Density rho_L    Sigma level    Kernel behavior
> 0.66 (high)    1.5 (tight)    Sharp, narrow envelope
0.33-0.66 (mid)  2.5 (medium)   Balanced
< 0.33 (low)     3.5 (broad)    Wide, smooth envelope
```


## File Structure

```
app/src/main/java/com/kukuh/gwi_odps/
  processing/
    MatPool.kt              Pre-allocated Mat buffers (zero-alloc)
    GwiEngine.kt            GWi + GWi+ODPS + AGWi algorithms
    MetricsCalculator.kt    Shannon Entropy + Mean + StdDev
    ImageProcessingPipeline.kt  Orchestrates 4 algorithms
    GwiFrameAnalyzer.kt     CameraX analyzer with frame-dropping
    GwiNativeBridge.kt      JNI bridge (optional NDK)
  ui/
    GwiOdpsScreen.kt        Compose UI (preview + grid + metrics)
  util/
    GallerySaver.kt         MediaStore save
  GwiOdpsApp.kt             OpenCV init
  MainActivity.kt           Entry point + permissions

app/src/main/cpp/
  CMakeLists.txt             NDK build config
  gwi_native.cpp             C++ GWi+ODPS + AGWi (optional)
```


## Build

1. Download OpenCV Android SDK 4.10.0 from opencv.org
2. Extract to project root as `sdk/`
3. Open in Android Studio, sync Gradle, run on device
4. NDK is optional: disable `externalNativeBuild` in build.gradle if not using


## Parameter Table

| Parameter      | GWi/ODPS (Paper 1) | AGWi (Paper 2)  |
|----------------|---------------------|-----------------|
| Kernel size    | 5x5                 | 7x7 (fixed)     |
| Wavelength     | 4.0 px              | 4.0 px          |
| Sigma          | 2.24 (fixed)        | 1.5/2.5/3.5     |
| Gamma          | 0.5                 | 0.5             |
| Psi            | 0.0                 | 0.0             |
| Orientations   | 8                   | 8               |
| ODPS distance  | 2 (lambda/2)        | n/a             |
| Histogram EQ   | yes                 | **no**          |
| Density blur   | n/a                 | 15x15 box       |
