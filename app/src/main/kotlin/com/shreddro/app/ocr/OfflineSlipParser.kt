package com.shreddro.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.googlecode.tesseract.android.TessBaseAPI
import com.shreddro.app.BuildConfig
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
        val bitmap = ImageDecoding.decodeScaled(candidate.bytes, MAX_SIDE_PX)
            ?: throw SlipParseException("Cannot decode image")

        try {
            val qrPayloads = runCatching {
                barcodeScanner.process(InputImage.fromBitmap(bitmap, 0)).await()
                    .mapNotNull { it.rawValue }
            }.getOrDefault(emptyList())

            val text = tessMutex.withLock {
                withContext(Dispatchers.Default) {
                    val api = ensureTess()
                    val prepared = prepareForOcr(bitmap)
                    try {
                        api.setImage(prepared)
                        api.utF8Text ?: ""
                    } finally {
                        api.clear()
                        if (prepared !== bitmap) prepared.recycle()
                    }
                }
            }
            Log.d(TAG, "${candidate.displayName}: ocr ${text.length} chars, ${qrPayloads.size} QR")
            dumpForFixture(candidate.displayName, text, qrPayloads)

            return template.parse(
                ExtractedSlipText(lines = text.lines(), qrPayloads = qrPayloads),
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * OCR pre-processing for Tesseract only (QR decode uses the original).
     *
     * K PLUS saves slips at ~990 px wide over a faint bank-building
     * watermark; on-device dumps showed the amount line dissolving into
     * garbage ("BAG eae OO OOM") while larger, flat-background slips read
     * cleanly. Upscaling small images to [OCR_MIN_SIDE_PX] on the long side
     * and flattening to high-contrast grayscale pushes the watermark to
     * white and gives Tesseract's line finder enough pixels per glyph.
     */
    private fun prepareForOcr(src: Bitmap): Bitmap {
        val longer = maxOf(src.width, src.height)
        val scale = if (longer < OCR_MIN_SIDE_PX) OCR_MIN_SIDE_PX.toFloat() / longer else 1f
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val contrast = OCR_CONTRAST
        val offset = (-0.5f * contrast + 0.5f) * 255f
        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, offset,
                        0f, contrast, 0f, 0f, offset,
                        0f, 0f, contrast, 0f, offset,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(src, null, Rect(0, 0, w, h), paint)
        return out
    }

    /**
     * Debug builds only: writes the raw OCR text (+ QR payloads) to
     * `filesDir/ocr-dumps/<image>.txt` so real-device output can be pulled
     * with `adb shell run-as` and checked into
     * `core/src/test/resources/ocr-dumps/` as a template-parser fixture.
     * Never runs in release: the text contains the user's slip contents.
     */
    private fun dumpForFixture(displayName: String, text: String, qrPayloads: List<String>) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val dir = File(context.filesDir, "ocr-dumps").apply { mkdirs() }
            val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            File(dir, "$safe.txt").writeText(
                buildString {
                    append(text)
                    if (qrPayloads.isNotEmpty()) {
                        append("\n\n# QR\n")
                        qrPayloads.forEach { append(it).append('\n') }
                    }
                },
            )
        }.onFailure { Log.w(TAG, "ocr dump failed", it) }
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
        const val TAG = "Shreddro.Parser"
        const val LANGS = "tha+eng"
        /** Keep full resolution for real screenshots; only shrink camera-sized images. */
        const val MAX_SIDE_PX = 3200
        /** Upscale target for small slips (K PLUS ≈ 990×1300) before Tesseract. */
        const val OCR_MIN_SIDE_PX = 2600
        const val OCR_CONTRAST = 1.6f
    }
}
