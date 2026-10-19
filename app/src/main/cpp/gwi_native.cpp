/**
 * gwi_native.cpp - JNI bridge for GWi+ODPS and AGWi pipelines.
 *
 * Author: Kukuh Yudhistiro, 2026
 *
 * Implements:
 *   1. GWi (Imaginary Gabor filter bank)
 *   2. GWi+ODPS (GWi + Orientation-aware Double-Peak Suppression)
 *   3. A-GWi (Adaptive GWi with per-pixel frequency/scale modulation)
 */

#include <jni.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <android/log.h>
#include <cmath>
#include <vector>
#include <algorithm>

#define TAG "GwiNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// Common Parameters
static const int    N_ORIENT   = 8;
static const double GAMMA      = 0.5;
static const double PSI        = 0.0;
static const double WAVELENGTH = 4.0;
static const int    ODPS_D     = 2;

// Paper 1 (GWi) Parameters
static const int    KSIZE_P1   = 5; // Changed to 5 to match Kotlin/Paper
static const double SIGMA_P1   = 2.24; // 0.56 * lambda

// Paper 2 (A-GWi) Parameters
static const int    KSIZE_P2   = 7;
static const double F_MIN      = 0.05;
static const double F_MAX      = 0.45;
static const double K_STEEP    = 25.0;
static const double ASPECT     = 0.5;
static const int    SOBEL_K    = 5;
static const int    BLUR_K     = 5;
static const int    N_LEVELS   = 101; // 0.00 to 1.00 in 0.01 steps

// Filter banks
static cv::Mat g_p1Bank[N_ORIENT];
static cv::Mat g_agwiBank[N_LEVELS][N_ORIENT];
static double g_angles[N_ORIENT];
static bool g_init = false;

static cv::Mat buildKernel(int k, double f0, double theta_deg,
                            double sig_x, double sig_y, double psi) {
    double th = theta_deg * CV_PI / 180.0;
    int half = k / 2;
    cv::Mat kern(k, k, CV_32FC1);
    for (int r = 0; r < k; r++) {
        float* p = kern.ptr<float>(r);
        for (int c = 0; c < k; c++) {
            double x = c - half, y = r - half;
            double xt = x*cos(th) + y*sin(th);
            double yt = -x*sin(th) + y*cos(th);
            double env = exp(-0.5 * (xt*xt/(sig_x*sig_x) + yt*yt/(sig_y*sig_y)));
            p[c] = (float)(env * sin(2*CV_PI*f0*xt + psi));
        }
    }
    return kern;
}

static void initBanks() {
    if (g_init) return;

    // Paper 1 Bank
    double f0_p1 = 1.0 / WAVELENGTH;
    for (int i = 0; i < N_ORIENT; i++) {
        double td = i * 22.5;
        g_angles[i] = td * CV_PI / 180.0;
        g_p1Bank[i] = buildKernel(KSIZE_P1, f0_p1, td, SIGMA_P1, SIGMA_P1/GAMMA, PSI);
    }

    // Paper 2 (AGWi) Bank
    for (int L = 0; L < N_LEVELS; L++) {
        double rho = L / 100.0;
        // Sigmoid mapping from density to frequency
        double f_sig = 1.0 / (1.0 + exp(-K_STEEP * (rho - 0.5)));
        double f0 = F_MIN + (F_MAX - F_MIN) * f_sig;
        // Gabor bandwidth relation: sigma = (1/(pi*f0)) * sqrt(ln2/2)
        double sigma_a = (1.0 / (CV_PI * f0)) * sqrt(log(2.0) / 2.0);
        double sx = sigma_a;
        double sy = sigma_a / ASPECT;

        for (int i = 0; i < N_ORIENT; i++) {
            g_agwiBank[L][i] = buildKernel(KSIZE_P2, f0, i * 22.5, sx, sy, PSI);
        }
    }

    g_init = true;
    LOGI("Filter banks initialized: GWi(8) and AGWi(101x8) kernels");
}

static void runBank(const cv::Mat& src, const cv::Mat* bank,
                     cv::Mat& edge, cv::Mat& orient) {
    edge = cv::Mat::zeros(src.size(), CV_32FC1);
    orient = cv::Mat::zeros(src.size(), CV_32FC1);
    for (int i = 0; i < N_ORIENT; i++) {
        cv::Mat resp, mag;
        cv::filter2D(src, resp, CV_32F, bank[i]);
        mag = cv::abs(resp);
        cv::Mat mask;
        cv::compare(mag, edge, mask, cv::CMP_GT);
        mag.copyTo(edge, mask);
        cv::Mat idx(src.size(), CV_32FC1, cv::Scalar(i));
        idx.copyTo(orient, mask);
    }
}

static void odps(const cv::Mat& edge, const cv::Mat& orient, cv::Mat& out) {
    int rows = edge.rows, cols = edge.cols;
    out = cv::Mat::zeros(rows, cols, CV_32FC1);
    const float* em = edge.ptr<float>(0);
    const float* om = orient.ptr<float>(0);
    float* op = out.ptr<float>(0);
    for (int y = ODPS_D; y < rows-ODPS_D; y++) {
        for (int x = ODPS_D; x < cols-ODPS_D; x++) {
            int i = y*cols+x;
            float c = em[i]; if (c <= 0) continue;
            int oi = std::clamp((int)om[i], 0, N_ORIENT-1);
            double nx = cos(g_angles[oi]), ny = sin(g_angles[oi]);
            int lx=(int)round(x-ODPS_D*nx), ly=(int)round(y-ODPS_D*ny);
            int rx=(int)round(x+ODPS_D*nx), ry=(int)round(y+ODPS_D*ny);
            if (lx<0||lx>=cols||ly<0||ly>=rows||rx<0||rx>=cols||ry<0||ry>=rows) continue;
            if (c >= em[ly*cols+lx] && c >= em[ry*cols+rx]) op[i] = c;
        }
    }
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_kukuh_agwi_processing_GwiNativeBridge_nativeGwi(
    JNIEnv*, jclass, jlong grayAddr, jlong outAddr) {
    initBanks();
    cv::Mat& gray = *(cv::Mat*)grayAddr;
    cv::Mat& output = *(cv::Mat*)outAddr;
    cv::Mat eq, norm;
    cv::equalizeHist(gray, eq);
    eq.convertTo(norm, CV_32FC1, 1.0/255.0);
    cv::Mat edge, orient;
    runBank(norm, g_p1Bank, edge, orient);
    cv::normalize(edge, output, 0, 255, cv::NORM_MINMAX);
    output.convertTo(output, CV_8UC1);
}

JNIEXPORT void JNICALL
Java_com_kukuh_agwi_processing_GwiNativeBridge_nativeGwiOdps(
    JNIEnv*, jclass, jlong grayAddr, jlong outAddr) {
    initBanks();
    cv::Mat& gray = *(cv::Mat*)grayAddr;
    cv::Mat& output = *(cv::Mat*)outAddr;

    cv::Mat eq, norm;
    cv::equalizeHist(gray, eq);
    eq.convertTo(norm, CV_32FC1, 1.0/255.0);

    cv::Mat edge, orient;
    runBank(norm, g_p1Bank, edge, orient);

    cv::Mat odpsOut;
    odps(edge, orient, odpsOut);

    cv::normalize(odpsOut, output, 0, 255, cv::NORM_MINMAX);
    output.convertTo(output, CV_8UC1);
}

JNIEXPORT void JNICALL
Java_com_kukuh_agwi_processing_GwiNativeBridge_nativeAGwi(
    JNIEnv*, jclass, jlong grayAddr, jlong outAddr) {
    initBanks();
    cv::Mat& gray = *(cv::Mat*)grayAddr;
    cv::Mat& output = *(cv::Mat*)outAddr;

    cv::Mat norm;
    gray.convertTo(norm, CV_32FC1, 1.0/255.0);

    // 1. Estimate Density (Sobel energy -> Normalize -> Blur)
    cv::Mat gx, gy, energy, density;
    cv::Sobel(norm, gx, CV_32F, 1, 0, SOBEL_K);
    cv::Sobel(norm, gy, CV_32F, 0, 1, SOBEL_K);
    cv::magnitude(gx, gy, energy);
    cv::normalize(energy, density, 0, 1, cv::NORM_MINMAX);
    cv::GaussianBlur(density, density, cv::Size(BLUR_K, BLUR_K), 0);

    // 2. Spatially Varying Convolution (SVC)
    int rows = norm.rows, cols = norm.cols;
    cv::Mat magOut = cv::Mat::zeros(norm.size(), CV_32FC1);

    // Padding for manual convolution
    cv::Mat padded;
    int pad = KSIZE_P2 / 2;
    cv::copyMakeBorder(norm, padded, pad, pad, pad, pad, cv::BORDER_REFLECT);

    // Per-pixel loop with OpenMP if available
    #pragma omp parallel for collapse(2)
    for (int y = 0; y < rows; y++) {
        for (int x = 0; x < cols; x++) {
            float rho = density.at<float>(y, x);
            int L = std::clamp((int)round(rho * 100.0f), 0, N_LEVELS - 1);

            float max_mag = 0;
            for (int o = 0; o < N_ORIENT; o++) {
                float res = 0;
                const cv::Mat& kern = g_agwiBank[L][o];
                for (int ky = 0; ky < KSIZE_P2; ky++) {
                    const float* k_ptr = kern.ptr<float>(ky);
                    const float* p_ptr = padded.ptr<float>(y + ky);
                    for (int kx = 0; kx < KSIZE_P2; kx++) {
                        res += p_ptr[x + kx] * k_ptr[kx];
                    }
                }
                float m = std::abs(res);
                if (m > max_mag) max_mag = m;
            }
            magOut.at<float>(y, x) = max_mag;
        }
    }

    cv::normalize(magOut, output, 0, 255, cv::NORM_MINMAX);
    output.convertTo(output, CV_8UC1);
}

JNIEXPORT jdouble JNICALL
Java_com_kukuh_agwi_processing_GwiNativeBridge_nativeEntropy(
    JNIEnv*, jclass, jlong addr) {
    cv::Mat& img = *(cv::Mat*)addr;
    int hs = 256; float rng[] = {0,256}; const float* hr = rng;
    cv::Mat hist;
    cv::calcHist(&img, 1, 0, cv::Mat(), hist, 1, &hs, &hr);
    int total = img.rows * img.cols;
    if (!total) return 0;
    double e = 0;
    for (int i = 0; i < 256; i++) {
        float c = hist.at<float>(i);
        if (c > 0) { double p = c/total; e -= p*log2(p); }
    }
    return e;
}

} // extern "C"
