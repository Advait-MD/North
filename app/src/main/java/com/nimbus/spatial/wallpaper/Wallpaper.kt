package com.nimbus.spatial.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.DrawableRes
import com.nimbus.spatial.R
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The two wallpapers ("at a place" and "elsewhere"): the user's own pictures or North's defaults,
 * and applying them. Everything here reads or decodes images, so call it off the main thread.
 */
object Wallpaper {
    private const val TAG = "Wallpaper"

    enum class Slot(val fileName: String, @DrawableRes val defaultImage: Int) {
        INSIDE("inside", R.drawable.in_fence),
        OUTSIDE("outside", R.drawable.out_fence);

        companion object {
            fun forState(insideAnyPlace: Boolean) = if (insideAnyPlace) INSIDE else OUTSIDE
        }
    }

    /** False when the phone (or a device policy) doesn't let apps change the wallpaper. */
    fun isSupported(context: Context): Boolean {
        val manager = WallpaperManager.getInstance(context)
        return manager.isWallpaperSupported && manager.isSetWallpaperAllowed
    }

    /**
     * Sets the wallpaper for "inside some place" or "inside no place", if the user has the
     * feature switched on. Called by GeofenceBroadcastReceiver and by the settings screen.
     * Returns true if the wallpaper was changed.
     */
    fun applyFor(context: Context, insideAnyPlace: Boolean): Boolean {
        if (!WallpaperSettings.isEnabled(context) || !isSupported(context)) return false

        val metrics = context.resources.displayMetrics
        val bitmap = load(context, Slot.forState(insideAnyPlace), metrics.widthPixels, metrics.heightPixels)
            ?: return false
        return try {
            WallpaperManager.getInstance(context)
                .setBitmap(bitmap, null, true, WallpaperSettings.target(context).wallpaperFlags)
            true
        } catch (e: Exception) { // IOException, or SecurityException under a device policy
            Log.e(TAG, "Failed to set wallpaper", e)
            false
        }
    }

    /** The slot's picture, scaled down to roughly [width] x [height] (never smaller). */
    fun load(context: Context, slot: Slot, width: Int, height: Int): Bitmap? {
        val custom = customFile(context, slot)
        return if (custom.exists()) decodeScaled(custom, width, height)
        else BitmapFactory.decodeResource(context.resources, slot.defaultImage)
    }

    fun hasCustom(context: Context, slot: Slot): Boolean = customFile(context, slot).exists()

    /**
     * Copies the picture at [source] into North's private storage, so it keeps working after
     * the original is moved or deleted. Returns false if it isn't a picture North can read.
     */
    fun saveCustom(context: Context, slot: Slot, source: Uri): Boolean {
        val target = customFile(context, slot)
        val temp = File(target.parentFile, "${slot.fileName}.tmp")
        target.parentFile?.mkdirs()
        return try {
            val input = context.contentResolver.openInputStream(source) ?: return false
            input.use { inp -> temp.outputStream().use { out -> inp.copyTo(out) } }
            val readable = decodeScaled(temp, 64, 64) != null
            if (readable) temp.renameTo(target) // replaces any previous picture
            readable
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't save wallpaper picture", e)
            false
        } finally {
            temp.delete()
        }
    }

    fun resetToDefault(context: Context, slot: Slot) {
        customFile(context, slot).delete()
    }

    private fun customFile(context: Context, slot: Slot) =
        File(context.applicationContext.filesDir, "wallpapers/${slot.fileName}")

    /**
     * Decodes [file] shrunk by a whole-number factor so it's still at least [minWidth] x
     * [minHeight]. Camera photos can be 50+ MB decoded, too big to hand to WallpaperManager.
     */
    private fun decodeScaled(file: File, minWidth: Int, minHeight: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies the photo's EXIF rotation and reads HEIC.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val scale = scaleFactor(info.size.width, info.size.height, minWidth, minHeight)
                decoder.setTargetSize(info.size.width / scale, info.size.height / scale)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // WallpaperManager needs pixels in memory
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = scaleFactor(bounds.outWidth, bounds.outHeight, minWidth, minHeight)
                }
                BitmapFactory.decodeFile(file.path, options)
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun scaleFactor(width: Int, height: Int, minWidth: Int, minHeight: Int): Int =
        max(1, min(width / max(1, minWidth), height / max(1, minHeight)))
}
