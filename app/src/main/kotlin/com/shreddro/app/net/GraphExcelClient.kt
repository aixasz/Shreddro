package com.shreddro.app.net

import com.shreddro.app.auth.AppAuthManager
import com.shreddro.core.gateway.BinaryStorageGateway
import com.shreddro.core.gateway.SpreadsheetGateway
import com.shreddro.core.model.CloudProvider
import com.shreddro.core.model.TransactionSlip
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Url
import java.time.Instant

/**
 * Microsoft workflow: appends rows straight into an Excel Online workbook on
 * OneDrive via the Graph workbook API, one table per bank.
 *
 * Endpoint used (per spec):
 *   POST /me/drive/items/{itemId}/workbook/tables/{tableName}/rows/add
 *
 * Bootstrap when a bank has no table yet:
 *   POST .../workbook/worksheets            (add sheet named {bank})
 *   POST .../workbook/tables/add            (address "{bank}!A1:F1", hasHeaders)
 *   PATCH header row via the new table's headerRowRange (done implicitly by
 *   creating the table over a pre-seeded header range).
 */
interface GraphApi {
    @POST("v1.0/me/drive/items/{itemId}/workbook/tables/{table}/rows/add")
    suspend fun addTableRow(
        @Header("Authorization") bearer: String,
        @Path("itemId") itemId: String,
        @Path("table") table: String,
        @Body body: GraphRowsAddRequest,
    ): Response<Unit>

    @GET("v1.0/me/drive/items/{itemId}/workbook/tables/{table}")
    suspend fun getTable(
        @Header("Authorization") bearer: String,
        @Path("itemId") itemId: String,
        @Path("table") table: String,
    ): Response<Unit>

    @POST("v1.0/me/drive/items/{itemId}/workbook/worksheets")
    suspend fun addWorksheet(
        @Header("Authorization") bearer: String,
        @Path("itemId") itemId: String,
        @Body body: GraphAddWorksheetRequest,
    ): Response<Unit>

    @POST("v1.0/me/drive/items/{itemId}/workbook/worksheets/{sheet}/range(address='A1:F1')/values")
    suspend fun seedHeaderRow(
        @Header("Authorization") bearer: String,
        @Path("itemId") itemId: String,
        @Path("sheet") sheet: String,
        @Body body: GraphRangeValuesRequest,
    ): Response<Unit>

    @POST("v1.0/me/drive/items/{itemId}/workbook/tables/add")
    suspend fun addTable(
        @Header("Authorization") bearer: String,
        @Path("itemId") itemId: String,
        @Body body: GraphAddTableRequest,
    ): Response<Unit>

    /** Simple upload (< 4 MB) into a OneDrive path. */
    @PUT
    suspend fun uploadSmallFile(
        @Url fullUrl: String,
        @Header("Authorization") bearer: String,
        @Body bytes: RequestBody,
    ): Response<Unit>
}

@Serializable data class GraphRowsAddRequest(val values: List<List<JsonPrimitive>>, val index: Int? = null)
@Serializable data class GraphAddWorksheetRequest(val name: String)
@Serializable data class GraphRangeValuesRequest(val values: List<List<String>>)
@Serializable data class GraphAddTableRequest(val address: String, val hasHeaders: Boolean = true)

class GraphExcelGateway(
    private val api: GraphApi,
    private val auth: AppAuthManager,
    /** Drive item id of the master .xlsx (user picks/creates it during onboarding). */
    private val workbookItemId: String,
) : SpreadsheetGateway {

    override val provider = CloudProvider.MICROSOFT

    override suspend fun appendRow(slip: TransactionSlip) {
        val bearer = "Bearer ${auth.freshAccessToken(CloudProvider.MICROSOFT)}"
        val table = tableNameFor(slip.bankKey)

        ensureTable(bearer, table, slip.bankKey)

        val row = GraphRowsAddRequest(
            values = listOf(
                listOf(
                    JsonPrimitive(slip.bankName),
                    JsonPrimitive(slip.dateTime),
                    JsonPrimitive(slip.amount),
                    JsonPrimitive(slip.sender),
                    JsonPrimitive(slip.receiver),
                    JsonPrimitive(slip.referenceId),
                ),
            ),
        )
        val resp = api.addTableRow(bearer, workbookItemId, table, row)
        if (!resp.isSuccessful) throw HttpException(resp)
    }

    private suspend fun ensureTable(bearer: String, table: String, bankKey: String) {
        val existing = api.getTable(bearer, workbookItemId, table)
        if (existing.isSuccessful) return
        if (existing.code() != 404) throw HttpException(existing)

        // Worksheet may already exist from a partial bootstrap — 409 is fine.
        val ws = api.addWorksheet(bearer, workbookItemId, GraphAddWorksheetRequest(bankKey))
        if (!ws.isSuccessful && ws.code() != 409) throw HttpException(ws)

        val header = api.seedHeaderRow(
            bearer, workbookItemId, bankKey,
            GraphRangeValuesRequest(listOf(HEADERS)),
        )
        if (!header.isSuccessful) throw HttpException(header)

        val created = api.addTable(
            bearer, workbookItemId,
            GraphAddTableRequest(address = "'$bankKey'!A1:F1"),
        )
        if (!created.isSuccessful && created.code() != 409) throw HttpException(created)
    }

    private fun tableNameFor(bankKey: String) =
        "Slips_" + bankKey.replace(Regex("[^A-Za-z0-9_]"), "_")

    private companion object {
        val HEADERS = listOf("bank_name", "date_time", "amount", "sender", "receiver", "reference_id")
    }
}

/** OneDrive raw-image sync: /Documents/Bank Slips/{BankName}/{file}. */
class OneDriveBinaryGateway(
    private val api: GraphApi,
    private val auth: AppAuthManager,
) : BinaryStorageGateway {

    override val provider = CloudProvider.MICROSOFT

    override suspend fun upload(bytes: ByteArray, fileName: String, bankKey: String) {
        val bearer = "Bearer ${auth.freshAccessToken(CloudProvider.MICROSOFT)}"
        val stamped = "${Instant.now().epochSecond}_$fileName"
        val url = "https://graph.microsoft.com/v1.0/me/drive/root:" +
            "/Documents/Bank Slips/$bankKey/$stamped:/content"
        val resp = api.uploadSmallFile(url, bearer, bytes.toRequestBody("image/jpeg".toMediaType()))
        if (!resp.isSuccessful) throw HttpException(resp)
    }
}
