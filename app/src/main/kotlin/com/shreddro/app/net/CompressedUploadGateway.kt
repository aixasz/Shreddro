package com.shreddro.app.net

import android.util.Log
import com.shreddro.app.ocr.SlipImageCompressor
import com.shreddro.core.gateway.BinaryStorageGateway
import com.shreddro.core.model.CloudProvider

/**
 * Decorator over a [BinaryStorageGateway]: the cloud copy of a slip is the
 * downsized JPEG produced by [SlipImageCompressor] (the same rule the local
 * archive uses when "Compress archive copies" is on, so both copies are the
 * same file). Both direct uploads and queued re-uploads pass through here
 * because the decorator wraps the gateway itself.
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
        if (enabled()) SlipImageCompressor.jpegName(originalName) else originalName

    override suspend fun upload(bytes: ByteArray, fileName: String, bankKey: String) {
        if (!enabled()) return delegate.upload(bytes, fileName, bankKey)
        val jpeg = SlipImageCompressor.toJpeg(bytes, fileName)
        if (jpeg == null) {
            // Undecodable image: upload untouched under its ORIGINAL name so
            // nothing is lost; the row's image_file may then differ — logged.
            Log.w(TAG, "$fileName: could not decode; uploading original bytes as-is")
            return delegate.upload(bytes, fileName, bankKey)
        }
        delegate.upload(jpeg, cloudFileName(fileName), bankKey)
    }

    private companion object {
        const val TAG = "Shreddro.Upload"
    }
}
