package com.boneink.picapica.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Fragment metadata stays off the wire and participates in both Coil and offline cache identity. */
object XiangxiangImages {
    fun mark(url: String, slices: Int): String = url.toHttpUrl().newBuilder().fragment("picapica-xx=$slices").build().toString()
    fun slices(url: okhttp3.HttpUrl): Int? = url.fragment?.removePrefix("picapica-xx=")?.toIntOrNull()?.takeIf { it in 2..64 }
    fun restore(source: Bitmap, slices: Int): Bitmap {
        require(slices in 2..64 && source.width >= slices)
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val width = source.width / slices
        // The site's long-image branch retains the original order.
        if (source.height >= 4000) canvas.drawBitmap(source, 0f, 0f, null)
        else for (i in 0 until slices) {
            val count = if (i == slices - 1) source.width - width * i else width
            val from = if (i == slices - 1) 0 else source.width - width * (i + 1)
            canvas.drawBitmap(source, Rect(from, 0, from + count, source.height), Rect(width * i, 0, width * i + count, source.height), null)
        }
        return output
    }
}

class XiangxiangImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val count = XiangxiangImages.slices(request.url)
        val response = chain.proceed(request)
        if (count == null || !response.isSuccessful) return response
        response.use {
            val body = it.body?.source() ?: throw IOException("图片内容为空")
            body.request(24L * 1024 * 1024 + 1)
            if (body.buffer.size > 24L * 1024 * 1024) throw IOException("图片过大")
            val bytes = body.readByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 24_000_000) throw IOException("图片尺寸无效")
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("图片无法解码")
            try {
                val restored = XiangxiangImages.restore(bitmap, count)
                try {
                    val output = ByteArrayOutputStream()
                    if (!restored.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("图片无法保存")
                    return response.newBuilder().removeHeader("Content-Encoding").removeHeader("Content-Length")
                        .body(output.toByteArray().toResponseBody("image/png".toMediaType())).build()
                } finally { restored.recycle() }
            } finally { bitmap.recycle() }
        }
    }
}
