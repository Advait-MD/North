package com.nimbus.spatial.geofence

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.DrawableRes

object Wallpaper {

    /** Sets both the home and lock-screen wallpaper. Decodes an image, so call it off the main thread. */
    fun set(context: Context, @DrawableRes imageResId: Int) {
        val bitmap = BitmapFactory.decodeResource(context.resources, imageResId) ?: return
        try {
            WallpaperManager.getInstance(context)
                .setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        } catch (e: Exception) { // IOException, or SecurityException if the device forbids it
            Log.e("Wallpaper", "Failed to set wallpaper", e)
        }
    }
}
