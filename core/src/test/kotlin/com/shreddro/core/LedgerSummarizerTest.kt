package com.shreddro.core

import com.shreddro.core.ledger.BankTotal
import com.shreddro.core.ledger.LedgerRecord
import com.shreddro.core.ledger.LedgerSummarizer
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerSummarizerTest {

    private fun record(bank: String, amount: Double, ref: String = "") = LedgerRecord(
        loggedAtUtc = "2026-09-03T08:00:00Z",
        bankName = bank,
        dateTime = "2026-09-03 10:00",
        amount = amount,
        sender = "A",
        receiver = "B",
        referenceId = ref,
    )

    @Test
    fun `empty ledger summarizes to zero`() {
        val summary = LedgerSummarizer.summarize(emptyList())
        assertEquals(0, summary.count)
        assertEquals(0.0, summary.total)
        assertEquals(emptyList(), summary.byBank)
    }

    @Test
    fun `single record is its own bank total`() {
        val summary = LedgerSummarizer.summarize(listOf(record("KBank", 250.5)))
        assertEquals(1, summary.count)
        assertEquals(250.5, summary.total)
        assertEquals(listOf(BankTotal("KBank", 1, 250.5)), summary.byBank)
    }

    @Test
    fun `banks are ordered by amount desc then name, trimmed names merge`() {
        val summary = LedgerSummarizer.summarize(
            listOf(
                record("SCB", 100.0),
                record("KBank", 300.0),
                record("Bangkok Bank", 100.0),
                record(" KBank ", 200.0),
                record("Krungthai", 400.0),
            ),
        )
        assertEquals(5, summary.count)
        assertEquals(1100.0, summary.total)
        assertEquals(
            listOf(
                BankTotal("KBank", 2, 500.0),
                BankTotal("Krungthai", 1, 400.0),
                BankTotal("Bangkok Bank", 1, 100.0),
                BankTotal("SCB", 1, 100.0),
            ),
            summary.byBank,
        )
    }

    @Test
    fun `totals are rounded to two decimals`() {
        // 0.1 + 0.2 + 0.7 == 1.0000000000000002 in binary floating point.
        val summary = LedgerSummarizer.summarize(
            listOf(record("SCB", 0.1), record("SCB", 0.2), record("SCB", 0.7), record("KBank", 1.006)),
        )
        assertEquals(2.01, summary.total)
        assertEquals(listOf(BankTotal("KBank", 1, 1.01), BankTotal("SCB", 3, 1.0)), summary.byBank)
    }
}
