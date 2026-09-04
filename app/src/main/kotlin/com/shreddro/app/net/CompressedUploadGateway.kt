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

    /**
     * The cloud name is decided by the setting alone — never by whether the
     * re-encode happened to be smaller — so a spreadsheet row written before
     * or without the upload cites exactly the file that will exist.
     */
    override fun cloudFileName(originalName: String): String =
        if (enabled()) originalName.substringBeforeLast('.', originalName) + ".jpg" else originalName

    override suspend fun upload(bytes: ByteArray, fileName: String, bankKey: String) {
        if (!enabled()) return delegate.upload(bytes, fileName, bankKey)
        val target = cloudFileName(fileName)
        val jpegBytes = compress(bytes, fileName)
            ?: if (isJpeg(fileName)) bytes else null // already JPEG: keep the bytes, only the name changes
        if (jpegBytes == null) {
            // Undecodable image: upload untouched under its ORIGINAL name so
            // nothing is lost; the row's image_file may then differ — logged.
            Log.w(TAG, "$fileName: could not decode; uploading original bytes as-is")
            return delegate.upload(bytes, fileName, bankKey)
        }
        delegate.upload(jpegBytes, target, bankKey)
    }

    /** JPEG re-encode when it saves space; null when the original JPEG already wins or cannot decode. */
    internal fun compress(bytes: ByteArray, fileName: String): ByteArray? {
        val bitmap = ImageDecoding.decodeScaled(bytes, MAX_SIDE_PX) ?: return null
        try {
            val scaled = fitLongSide(bitmap, MAX_SIDE_PX)
            val out = ByteArrayOutputStream(bytes.size / 4)
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== bitmap) scaled.recycle()
            val jpeg = out.toByteArray()
            if (jpeg.size >= bytes.size && isJpeg(fileName)) {
                Log.d(TAG, "$fileName: kept original JPEG (${bytes.size} B <= ${jpeg.size} B)")
                return null
            }
            Log.d(TAG, "$fileName -> ${cloudFileName(fileName)}: ${bytes.size} B -> ${jpeg.size} B (${scaled.width}x${scaled.height})")
            return jpeg
        } finally {
            bitmap.recycle()
        }
    }

    private fun isJpeg(name: String) =
        name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg")

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
