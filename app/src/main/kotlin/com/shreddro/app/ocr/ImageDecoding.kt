package com.shreddro.app.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Memory-bounded bitmap decoding for the OCR stages.
 *
 * A 200 MP camera JPEG decodes to >700 MB of ARGB pixels, far past the
 * default 256 MB heap; the first device scan died on exactly that. Slips are
 * phone screenshots (≤ ~1440×3200), so downsampling anything larger costs
 * no OCR accuracy while keeping a single decode under ~40 MB.
 */
internal object ImageDecoding {

    /**
     * Decodes [bytes] with a power-of-two `inSampleSize` chosen so the longer
     * side ends up at or below [maxSide]. Returns null for undecodable data.
     */
    fun decodeScaled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        var longer = maxOf(bounds.outWidth, bounds.outHeight)
        while (longer / 2 >= maxSide) {
            sample *= 2
            longer /= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
