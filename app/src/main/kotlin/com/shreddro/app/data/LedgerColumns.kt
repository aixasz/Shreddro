package com.shreddro.app.data

/**
 * The ONE header row every human-facing ledger shows — Google Sheet, Excel
 * workbook and the local .xlsx mirror. Column ORDER is the contract (readers
 * map by position, see net/CloudRows.kt); names are free to be readable.
 * The local CSV keeps its machine-friendly snake_case header (CsvFormatter).
 */
object LedgerColumns {
    val HEADERS: List<String> = listOf(
        "Logged at (UTC)",
        "Bank",
        "Date/time",
        "Amount (THB)",
        "Sender",
        "Receiver",
        "Reference",
        "Image file",
    )

    /** First header cell — used to detect an old snake_case or missing header row. */
    val FIRST: String get() = HEADERS.first()
}
