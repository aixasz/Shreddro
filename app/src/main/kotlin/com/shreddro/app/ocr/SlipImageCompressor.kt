package com.shreddro.app.ocr

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * The ONE downsizing rule for slip images, shared by the local archive and
 * the cloud uploads so both copies are the same file: longest side ≤ 1600 px,
 * JPEG quality 85. Screenshots are 1080–1440 px wide, so every field and the
 * verification QR stay crisp; real slips shrink ~10× (PNG) or stay as-is
 * (already-small JPEG).
 */
object SlipImageCompressor {

    /** Screenshots are 1080–1440 px wide; 1600 keeps every glyph and the QR crisp. */
    const val MAX_SIDE_PX = 1600
    const val JPEG_QUALITY = 85
    private const val TAG = "Shreddro.Compress"

    /** `<base>.jpg` — the name a compressed copy carries wherever it is stored. */
    fun jpegName(originalName: String): String =
        originalName.substringBeforeLast('.', originalName) + ".jpg"

    fun isJpeg(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg")

    /**
     * JPEG bytes to store for [bytes]: the re-encoded image when that saves
     * space or changes the format, the original bytes when they are already a
     * JPEG that re-encoding would not shrink, or null when the image cannot
     * be decoded (caller keeps the original untouched).
     */
    fun toJpeg(bytes: ByteArray, fileName: String): ByteArray? {
        val bitmap = ImageDecoding.decodeScaled(bytes, MAX_SIDE_PX) ?: return null
        try {
            val scaled = fitLongSide(bitmap, MAX_SIDE_PX)
            val out = ByteArrayOutputStream(bytes.size / 4)
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== bitmap) scaled.recycle()
            val jpeg = out.toByteArray()
            if (jpeg.size >= bytes.size && isJpeg(fileName)) {
                Log.d(TAG, "$fileName: kept original JPEG (${bytes.size} B <= ${jpeg.size} B)")
                return bytes
            }
            Log.d(TAG, "$fileName -> ${jpegName(fileName)}: ${bytes.size} B -> ${jpeg.size} B (${scaled.width}x${scaled.height})")
            return jpeg
        } finally {
            bitmap.recycle()
        }
    }

    /** decodeScaled only halves by powers of two; finish with an exact resize. */
    private fun fitLongSide(src: Bitmap, maxSide: Int): Bitmap {
        val longer = maxOf(src.width, src.height)
        if (longer <= maxSide) return src
        val scale = maxSide.toFloat() / longer
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
