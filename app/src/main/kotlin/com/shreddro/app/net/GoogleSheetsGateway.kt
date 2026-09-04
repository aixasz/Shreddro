package com.shreddro.app.net

import android.util.Log
import com.shreddro.app.auth.AppAuthManager
import com.shreddro.core.gateway.SpreadsheetGateway
import com.shreddro.core.ledger.CloudLedger
import com.shreddro.core.ledger.LedgerRecord
import com.shreddro.core.model.CloudProvider
import com.shreddro.core.model.TransactionSlip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

/**
 * Google Sheets ledger: ONE central spreadsheet, `Shreddro/Shreddro
 * Transactions` in the user's own Drive, with a `bank_name` column (slip
 * images still live per bank under `Shreddro/<bank>/`).
 *
 * The file is created (with a header row) right after the root folder when
 * an account is linked, and only if a spreadsheet of that name is not
 * already there; every slip afterwards is one appended row. Uses the Sheets API with the SAME `drive.file` token as the
 * image uploads — Sheets accepts that scope for spreadsheets the app created,
 * so no extra consent scope and no user-deployed Apps Script are needed.
 *
 * Console prerequisite: both the Google Drive API and the Google Sheets API
 * must be enabled on the OAuth client's project.
 */
class GoogleSheetsGateway(
    private val auth: AppAuthManager,
    private val client: OkHttpClient,
    private val files: GoogleDriveFiles,
    /** Called with the spreadsheet URL so the UI can deep-link to Sheets. */
    private val onSheetUrl: (String) -> Unit = {},
    /** Called with the root `Shreddro` folder URL. */
    private val onFolderUrl: (String) -> Unit = {},
) : SpreadsheetGateway, CloudProvisioner, CloudLedger {

    override val provider = CloudProvider.GOOGLE
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var sheetId: String? = null

    /**
     * Keys of every data row (A2:H) in the central sheet. UNFORMATTED_VALUE
     * keeps the amount numeric so it round-trips through [LedgerRecord.key]
     * exactly like a local row.
     */
    override suspend fun existingKeys(): Set<String> {
        val token = auth.freshAccessToken(CloudProvider.GOOGLE)
        return withContext(Dispatchers.IO) {
            val id = resolveSpreadsheet(token)
            val url = "https://sheets.googleapis.com/v4/spreadsheets/$id/values/A2:H" +
                "?valueRenderOption=UNFORMATTED_VALUE"
            val rows = client.newCall(
                Request.Builder().url(url).header("Authorization", "Bearer $token").build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Sheets read failed HTTP ${resp.code}: ${resp.body?.string()?.take(400)}")
                }
                json.parseToJsonElement(resp.body?.string() ?: "{}").jsonObject["values"]?.jsonArray
                    ?: JsonArray(emptyList())
            }
            rows.mapTo(HashSet()) { row ->
                CloudRows.toRecord(row.jsonArray.map(CloudRows::cellText)).key
            }
        }
    }

    override suspend fun append(record: LedgerRecord) = appendRow(record.toSlip(), record.imageFile)

    /** Root folder + the central spreadsheet (+ header), find-or-create. */
    override suspend fun provision(bankKeys: Collection<String>) {
        val token = auth.freshAccessToken(CloudProvider.GOOGLE)
        withContext(Dispatchers.IO) {
            onFolderUrl(files.rootFolderUrl(token))
            for (bank in bankKeys) files.ensureBankFolder(token, bank)
            resolveSpreadsheet(token)
        }
        Log.d(TAG, "Sheets: provisioned root, ${bankKeys.size} bank folder(s), central sheet")
    }

    override suspend fun appendRow(slip: TransactionSlip, imageFileName: String) {
        val token = auth.freshAccessToken(CloudProvider.GOOGLE)
        val sheetId = resolveSpreadsheet(token)

        val row = buildJsonArray {
            add(JsonPrimitive(Instant.now().toString()))
            add(JsonPrimitive(slip.bankName))
            add(JsonPrimitive(slip.dateTime))
            add(JsonPrimitive(slip.amount))
            add(JsonPrimitive(slip.sender))
            add(JsonPrimitive(slip.receiver))
            add(JsonPrimitive(slip.referenceId))
            add(JsonPrimitive(imageFileName))
        }
        val body = buildJsonObject { put("values", buildJsonArray { add(row) }) }.toString()
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$sheetId/values/" +
            "A1:H1:append?valueInputOption=RAW&insertDataOption=INSERT_ROWS"
        client.newCall(
            Request.Builder().url(url).header("Authorization", "Bearer $token")
                .post(body.toRequestBody(GoogleDriveFiles.JSON_MEDIA)).build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Sheets append failed HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
            }
        }
        Log.d(TAG, "Sheets: appended ${slip.bankKey} row (${slip.amount})")
    }

    /**
     * Finds or creates `Shreddro/Shreddro Transactions`. On first resolution
     * per process the header row is verified, so a spreadsheet created by a
     * run that died before seeding still gets its header.
     */
    private fun resolveSpreadsheet(token: String): String {
        sheetId?.let { return it }
        val root = files.ensureFolder(token, GoogleDriveFiles.ROOT_FOLDER, parentId = null)
        val id = files.findByName(token, SHEET_NAME, root, GoogleDriveFiles.SHEET_MIME)
            ?: files.createMetadata(token, SHEET_NAME, root, GoogleDriveFiles.SHEET_MIME)
                .also { Log.d(TAG, "Sheets: created $SHEET_NAME") }
        if (headerCell(token, id).isNullOrBlank()) seedHeader(token, id)
        sheetId = id
        onSheetUrl("https://docs.google.com/spreadsheets/d/$id")
        return id
    }

    private fun headerCell(token: String, sheetId: String): String? =
        client.newCall(
            Request.Builder()
                .url("https://sheets.googleapis.com/v4/spreadsheets/$sheetId/values/A1:A1")
                .header("Authorization", "Bearer $token").build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Sheets read failed HTTP ${resp.code}: ${resp.body?.string()?.take(400)}")
            json.parseToJsonElement(resp.body?.string() ?: "{}").jsonObject["values"]
                ?.jsonArray?.firstOrNull()?.jsonArray?.firstOrNull()?.toString()?.trim('"')
        }

    private fun seedHeader(token: String, sheetId: String) {
        val body = buildJsonObject {
            put("values", buildJsonArray { add(buildJsonArray { HEADERS.forEach { add(JsonPrimitive(it)) } }) })
        }.toString()
        client.newCall(
            Request.Builder()
                .url("https://sheets.googleapis.com/v4/spreadsheets/$sheetId/values/A1:H1?valueInputOption=RAW")
                .header("Authorization", "Bearer $token")
                .put(body.toRequestBody(GoogleDriveFiles.JSON_MEDIA)).build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Sheets header seed failed HTTP ${resp.code}: ${resp.body?.string()?.take(400)}")
        }
    }

    private companion object {
        const val TAG = "Shreddro.Upload"
        const val SHEET_NAME = "Shreddro Transactions"
        val HEADERS = listOf(
            "logged_at_utc", "bank_name", "date_time", "amount", "sender", "receiver", "reference_id",
            "image_file",
        )
    }
}
