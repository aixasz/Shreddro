package com.shreddro.core.parse

import com.shreddro.core.gateway.SlipCandidate
import com.shreddro.core.gateway.SlipParseException
import com.shreddro.core.gateway.SlipParser
import com.shreddro.core.model.TransactionSlip

/**
 * Chain-of-responsibility over [SlipParser]s: first success wins.
 *
 * Canonical chain (offline-first):
 *   1. on-device template parser (OCR + per-bank rules; free, private, offline)
 *   2. Vision LLM (online; handles unknown banks and layout redesigns)
 * A parser signals "not mine / can't read it" via [SlipParseException] and the
 * chain falls through; any other exception (e.g. network I/O) also falls
 * through so an offline device still reaches later offline parsers or review.
 */
class CompositeSlipParser(private val parsers: List<SlipParser>) : SlipParser {

    init {
        require(parsers.isNotEmpty()) { "CompositeSlipParser needs at least one parser" }
    }

    override suspend fun parse(candidate: SlipCandidate): TransactionSlip {
        var last: Exception? = null
        for (parser in parsers) {
            try {
                return parser.parse(candidate)
            } catch (e: SlipParseException) {
                last = e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last as? SlipParseException
            ?: SlipParseException("All parsers failed", last)
    }
}
