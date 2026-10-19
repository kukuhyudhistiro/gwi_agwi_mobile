package com.kukuh.agwi.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

object GallerySaver {
    fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "odps_$ts.jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/GWi-ODPS")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            ?: return false
        return try {
            val stream = resolver.openOutputStream(uri) ?: return false
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            stream.flush(); stream.close()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, cv, null, null)
            }
            Toast.makeText(context, "Saved: $filename", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
