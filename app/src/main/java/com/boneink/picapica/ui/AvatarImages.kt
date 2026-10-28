package com.boneink.picapica.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream

/** Bounds decoding before allocation; picker grants access only to the chosen image. */
internal fun prepareAvatar(context: Context, uri: Uri): ByteArray {
    val resolver = context.contentResolver
    val decoded = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val scale = 1024f / maxOf(info.size.width, info.size.height).coerceAtLeast(1024)
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        val options = BitmapFactory.Options().apply {
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1024) inSampleSize *= 2
        }
        val raw = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) } ?: error("无法读取图片")
        val orientation = resolver.openInputStream(uri).use { it?.let { stream -> ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also { if (it !== raw) raw.recycle() }
    }
    try {
        val edge = minOf(decoded.width, decoded.height)
        val crop = Bitmap.createBitmap(decoded, (decoded.width - edge) / 2, (decoded.height - edge) / 2, edge, edge)
        val scaled = Bitmap.createScaledBitmap(crop, 200, 200, true)
        val opaque = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        try {
            Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
            return ByteArrayOutputStream().use { out -> check(opaque.compress(Bitmap.CompressFormat.JPEG, 90, out)); out.toByteArray() }
        } finally {
            opaque.recycle()
            if (scaled !== decoded && scaled !== crop) scaled.recycle()
            if (crop !== decoded) crop.recycle()
        }
    } finally { decoded.recycle() }
}
