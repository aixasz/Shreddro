package com.shreddro.core.parse

import com.shreddro.core.gateway.SlipParseException
import com.shreddro.core.model.TransactionSlip
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Turns raw Vision-LLM text output into a validated [TransactionSlip].
 * LLMs frequently wrap JSON in markdown fences or prepend prose; this parser
 * extracts the first JSON object and enforces the contract strictly.
 */
object SlipJsonParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String): TransactionSlip {
        val extracted = extractJsonObject(raw)
            ?: throw SlipParseException("No JSON object found in model output")
        val slip = try {
            json.decodeFromString(TransactionSlip.serializer(), extracted)
        } catch (e: SerializationException) {
            throw SlipParseException("Model output does not match the slip contract", e)
        } catch (e: IllegalArgumentException) {
            throw SlipParseException("Model output does not match the slip contract", e)
        }
        validate(slip)
        return slip
    }

    private fun validate(slip: TransactionSlip) {
        if (slip.bankName.isBlank()) throw SlipParseException("bank_name is blank")
        if (slip.amount.isNaN() || slip.amount < 0.0) {
            throw SlipParseException("amount is invalid: ${slip.amount}")
        }
        if (slip.dateTime.isBlank()) throw SlipParseException("date_time is blank")
    }

    /** Extracts the first balanced top-level JSON object, tolerating fences/prose. */
    internal fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
