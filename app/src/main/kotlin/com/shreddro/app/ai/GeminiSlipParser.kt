package com.shreddro.app.ai

import android.util.Base64
import com.shreddro.core.gateway.SlipCandidate
import com.shreddro.core.gateway.SlipParseException
import com.shreddro.core.gateway.SlipParser
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.parse.SlipJsonParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Vision-LLM parser backed by Gemini Flash (generateContent, inline image).
 * Provider-pluggable: swap this adapter for a GPT-4o-mini implementation
 * without touching :core — both must return the exact contract handled by
 * [SlipJsonParser].
 */
class GeminiSlipParser(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val model: String = "gemini-2.0-flash",
) : SlipParser {

    override suspend fun parse(candidate: SlipCandidate): TransactionSlip {
        val body = buildRequestJson(candidate.bytes)
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val responseText = execute(request)
        val modelText = extractModelText(responseText)
            ?: throw SlipParseException("Gemini response contained no candidate text")
        return SlipJsonParser.parse(modelText)
    }

    private fun buildRequestJson(imageBytes: ByteArray): String {
        val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val root = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", PROMPT) })
                        add(buildJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", "image/jpeg")
                                put("data", b64)
                            }
                        })
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.0)
                put("response_mime_type", "application/json")
            }
        }
        return root.toString()
    }

    private fun extractModelText(raw: String): String? = try {
        Json.parseToJsonElement(raw).jsonObject["candidates"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }

    private suspend fun execute(request: Request): String =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use {
                        val text = it.body?.string() ?: ""
                        if (!it.isSuccessful) {
                            cont.resumeWithException(
                                SlipParseException("Gemini HTTP ${it.code}: ${text.take(300)}"),
                            )
                        } else {
                            cont.resume(text)
                        }
                    }
                }
            })
        }

    private companion object {
        val PROMPT = """
            You are a Thai banking-slip extraction engine. Read the attached
            slip image (transfer, bill payment/จ่ายบิล, or merchant
            payment/ชำระเงิน; Thai and/or English) and return ONLY a JSON
            object with EXACTLY these keys and types, no markdown, no prose:
            {
              "bank_name": "string — the SENDER'S issuing bank in English (e.g. KBank, SCB, Krungthai, Bangkok Bank, Krungsri, TTB, GSB); infer from the app logo/branding when not written",
              "date_time": "string — exactly as printed on the slip, including Thai month names and Buddhist-era years (e.g. '30 ส.ค. 2569 - 14:16')",
              "amount": number — the main amount (จำนวนเงิน/จำนวน/Amount) in THB as a plain JSON number: no thousands separators, no quotes, e.g. 1790.00 not 1,790.00,
              "sender": "string — sender/From name as printed",
              "receiver": "string — receiver/To/merchant/biller name as printed",
              "reference_id": "string — the primary transaction reference, by label priority: เลขที่รายการ > Transaction reference > รหัสอ้างอิง > รหัสธุรกรรม > Bank reference no."
            }
            If a field is unreadable use "" (or 0 for amount). Never invent data.
        """.trimIndent()
    }
}
