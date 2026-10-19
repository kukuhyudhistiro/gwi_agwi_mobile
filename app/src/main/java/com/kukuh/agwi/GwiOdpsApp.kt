package com.kukuh.agwi

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class GwiOdpsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (OpenCVLoader.initLocal()) Log.i("GwiOdps", "OpenCV loaded")
        else Log.e("GwiOdps", "OpenCV init failed")
    }
}
