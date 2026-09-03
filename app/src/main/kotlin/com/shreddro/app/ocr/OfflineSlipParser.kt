package com.shreddro.app.ocr

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.googlecode.tesseract.android.TessBaseAPI
import com.shreddro.core.gateway.SlipCandidate
import com.shreddro.core.gateway.SlipParseException
import com.shreddro.core.gateway.SlipParser
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.parse.ExtractedSlipText
import com.shreddro.core.parse.ThaiSlipTemplateParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 100% on-device slip parser: Tesseract OCR (tha+eng, fast models bundled in
 * assets/tessdata) + ML Kit QR decode feed the pure-Kotlin
 * [ThaiSlipTemplateParser]. No network, no API key, image never leaves the
 * device.
 */
class OfflineSlipParser(private val context: Context) : SlipParser {

    private val template = ThaiSlipTemplateParser()
    private val tessMutex = Mutex() // TessBaseAPI is not thread-safe
    private var tess: TessBaseAPI? = null

    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )

    override suspend fun parse(candidate: SlipCandidate): TransactionSlip {
        val bitmap = BitmapFactory.decodeByteArray(candidate.bytes, 0, candidate.bytes.size)
            ?: throw SlipParseException("Cannot decode image")

        val qrPayloads = runCatching {
            barcodeScanner.process(InputImage.fromBitmap(bitmap, 0)).await()
                .mapNotNull { it.rawValue }
        }.getOrDefault(emptyList())

        val text = tessMutex.withLock {
            withContext(Dispatchers.Default) {
                val api = ensureTess()
                try {
                    api.setImage(bitmap)
                    api.utF8Text ?: ""
                } finally {
                    api.clear()
                }
            }
        }

        return template.parse(
            ExtractedSlipText(lines = text.lines(), qrPayloads = qrPayloads),
        )
    }

    /** Copies the bundled traineddata out of assets on first use, then inits. */
    private fun ensureTess(): TessBaseAPI {
        tess?.let { return it }
        val dataDir = File(context.filesDir, "tesseract")
        val tessdata = File(dataDir, "tessdata").apply { mkdirs() }
        for (lang in LANGS.split("+")) {
            val target = File(tessdata, "$lang.traineddata")
            if (!target.exists()) {
                context.assets.open("tessdata/$lang.traineddata").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
        val api = TessBaseAPI()
        if (!api.init(dataDir.absolutePath, LANGS)) {
            api.recycle()
            throw SlipParseException("Tesseract init failed")
        }
        tess = api
        return api
    }

    private companion object {
        const val LANGS = "tha+eng"
    }
}
