package com.shreddro.app.net

import android.graphics.Bitmap
import android.util.Log
import com.shreddro.app.ocr.ImageDecoding
import com.shreddro.core.gateway.BinaryStorageGateway
import com.shreddro.core.model.CloudProvider
import java.io.ByteArrayOutputStream

/**
 * Decorator over a [BinaryStorageGateway]: the CLOUD copy of a slip is a
 * downsized JPEG, the LOCAL archive stays the byte-exact original.
 *
 * Why here and not in the vault: the purge-safety invariant hashes the
 * archive against the gallery original, so the archive must never be
 * re-encoded. Uploads, however, only need the slip to stay fully legible
 * (every field and the verification QR), which a 1600 px JPEG at quality 85
 * preserves — real slips shrink from ~1 MB PNG / 300 KB JPEG to 100–200 KB.
 * Both direct uploads and queued re-uploads pass through here because the
 * decorator wraps the gateway itself.
 *
 * Falls back to the original bytes whenever compression does not help or
 * the image cannot be decoded (never lose a slip to an optimisation).
 */
class CompressedUploadGateway(
    private val delegate: BinaryStorageGateway,
    private val enabled: () -> Boolean,
) : BinaryStorageGateway {

    override val provider: CloudProvider get() = delegate.provider

    override suspend fun upload(bytes: ByteArray, fileName: String, bankKey: String) {
        if (!enabled()) return delegate.upload(bytes, fileName, bankKey)
        val (outBytes, outName) = compress(bytes, fileName) ?: (bytes to fileName)
        delegate.upload(outBytes, outName, bankKey)
    }

    internal fun compress(bytes: ByteArray, fileName: String): Pair<ByteArray, String>? {
        val bitmap = ImageDecoding.decodeScaled(bytes, MAX_SIDE_PX) ?: return null
        try {
            val scaled = fitLongSide(bitmap, MAX_SIDE_PX)
            val out = ByteArrayOutputStream(bytes.size / 4)
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== bitmap) scaled.recycle()
            val jpeg = out.toByteArray()
            if (jpeg.size >= bytes.size) {
                Log.d(TAG, "$fileName: kept original (${bytes.size} B ≤ ${jpeg.size} B)")
                return null
            }
            val newName = fileName.substringBeforeLast('.', fileName) + ".jpg"
            Log.d(TAG, "$fileName -> $newName: ${bytes.size} B -> ${jpeg.size} B (${scaled.width}x${scaled.height})")
            return jpeg to newName
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

    private companion object {
        const val TAG = "Shreddro.Upload"
        /** Screenshots are 1080–1440 px wide; 1600 keeps every glyph and the QR crisp. */
        const val MAX_SIDE_PX = 1600
        const val JPEG_QUALITY = 85
    }
}
