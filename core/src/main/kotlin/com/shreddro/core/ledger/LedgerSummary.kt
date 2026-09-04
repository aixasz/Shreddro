package com.shreddro.core.ledger

data class BankTotal(val bankName: String, val count: Int, val amount: Double)

/** [byBank] is sorted by amount descending, then bank name ascending. */
data class LedgerSummary(val count: Int, val total: Double, val byBank: List<BankTotal>)

object LedgerSummarizer {

    fun summarize(records: List<LedgerRecord>): LedgerSummary {
        val byBank = records
            .groupBy { it.bankName.trim() }
            .map { (bank, rows) -> BankTotal(bank, rows.size, round2(rows.sumOf { it.amount })) }
            .sortedWith(compareByDescending<BankTotal> { it.amount }.thenBy { it.bankName })
        return LedgerSummary(
            count = records.size,
            total = round2(records.sumOf { it.amount }),
            byBank = byBank,
        )
    }

    private fun round2(value: Double): Double = kotlin.math.round(value * 100) / 100
}
