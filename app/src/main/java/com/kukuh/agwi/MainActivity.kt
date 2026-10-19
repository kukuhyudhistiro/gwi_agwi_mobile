package com.kukuh.agwi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kukuh.agwi.ui.GwiOdpsScreen

class MainActivity : ComponentActivity() {
    private var cameraGranted = mutableStateOf(false)
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { cameraGranted.value = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted.value) permLauncher.launch(Manifest.permission.CAMERA)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    if (cameraGranted.value) GwiOdpsScreen()
                    else Column(Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("GWi+AGWi Edge Detector", color = Color.White,
                            fontSize = 20.sp, fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Text("Camera permission required.", color = Color.Gray,
                            fontSize = 14.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1B5E20))) {
                            Text("Grant Camera Permission")
                        }
                    }
                }
            }
        }
    }
}
