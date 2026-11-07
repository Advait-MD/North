package com.nimbus.spatial

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.DrawableRes
import java.io.IOException

object WallpaperUtils {

    /** Sets both the home and lock-screen wallpaper. Decodes a bitmap, so call off the main thread. */
    fun setWallpaper(context: Context, @DrawableRes imageResId: Int) {
        val bitmap = BitmapFactory.decodeResource(context.resources, imageResId) ?: return
        try {
            WallpaperManager.getInstance(context)
                .setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        } catch (e: IOException) {
            Log.e("WallpaperUtils", "Failed to set wallpaper", e)
        } catch (e: SecurityException) {
            Log.e("WallpaperUtils", "Not allowed to set wallpaper", e)
        }
    }
}
