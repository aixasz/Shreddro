package com.shreddro.app.net

import android.util.Log
import com.shreddro.app.auth.AppAuthManager
import com.shreddro.app.data.LedgerColumns
import com.shreddro.core.gateway.BinaryStorageGateway
import com.shreddro.core.gateway.SpreadsheetGateway
import com.shreddro.core.ledger.CloudLedger
import com.shreddro.core.ledger.LedgerRecord
import com.shreddro.core.model.CloudProvider
import com.shreddro.core.model.TransactionSlip
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Url
import java.net.URLEncoder
import java.time.Instant

/**
 * Microsoft Graph endpoints for the OneDrive layout
 * `Shreddro/<bank>/<file>` + the central `Shreddro/Shreddro Transactions.xlsx`.
 */
interface GraphApi {
    @POST("v1.0/me/drive/items/{itemId}/workbook/tables/{table}/rows/add")
    suspend fun addTableRow(
        @Header("Authorization") bearer: String,
        @Path("itemId") itemId: String,
        @Path("table") table: String,
        @Body body: GraphRowsAddRequest,
    ): Response<Unit>

    /** Path-addressed lookup (folder or file) — 404 when it does not exist. */
    @GET
    suspend fun getItemByUrl(
        @Url fullUrl: String,
        @Header("Authorization") bearer: String,
    ): Response<GraphDriveItem>

    @GET("v1.0/me/drive/items/{itemId}/workbook/tables/{table}")
    suspend fun getTable(
        @Header("Authorization") bearer: String,
        @Path("itemId") itemId: String,
        @Path("table") table: String,
    ): Response<Unit>

    /** All data rows of a table (`{ "value": [ { "values": [[...]] } ] }`); 404 when the table is absent. */
    @GET("v1.0/me/drive/items/{itemId}/workbook/tables/{table}/rows")
    suspend fun listTableRows(
        @Header("Authorization") bearer: String,
        @Path("itemId") itemId: String,
        @Path("table") table: String,
    ): Response<GraphTableRowsResponse>

    /** Follows an `@odata.nextLink` from [listTableRows]. */
    @GET
    suspend fun listTableRowsByUrl(
        @Url fullUrl: String,
        @Header("Authorization") bearer: String,
    ): Response<GraphTableRowsResponse>

    /** 200 when the worksheet exists, 404 otherwise. */
    @GET("v1.0/me/drive/items/{itemId}/workbook/worksheets/{sheet}")
    suspend fun getWorksheet(
        @Header("Authorization") bearer: String,
        @Path("itemId") itemId: String,
        @Path("sheet") sheet: String,
    ): Response<Unit>

    @POST("v1.0/me/drive/items/{itemId}/workbook/worksheets")
    suspend fun addWorksheet(
        @Header("Authorization") bearer: String,
        @Path("itemId") itemId: String,
        @Body body: GraphAddWorksheetRequest,
    ): Response<Unit>

    /**
     * Writes cell values into a fixed range. Graph exposes range values via
     * PATCH on the range resource itself — the earlier POST to `…/values`
     * was not a valid endpoint, which is why workbooks came up header-less.
     */
    @PATCH("v1.0/me/drive/items/{itemId}/workbook/worksheets/{sheet}/range(address='A1:H1')")
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

    /** Creates a child folder under a path-addressed parent; 409 when it already exists. */
    @POST
    suspend fun createFolder(
        @Url fullUrl: String,
        @Header("Authorization") bearer: String,
        @Body body: GraphCreateFolderRequest,
    ): Response<GraphDriveItem>

    /** Simple upload (< 4 MB) into a OneDrive path; returns the created item. */
    @PUT
    suspend fun uploadSmallFile(
        @Url fullUrl: String,
        @Header("Authorization") bearer: String,
        @Body bytes: RequestBody,
    ): Response<GraphDriveItem>
}

@Serializable data class GraphDriveItem(val id: String? = null, val webUrl: String? = null)
@Serializable data class GraphRowsAddRequest(val values: List<List<JsonPrimitive>>, val index: Int? = null)
@Serializable data class GraphTableRowsResponse(
    val value: List<GraphTableRow> = emptyList(),
    @kotlinx.serialization.SerialName("@odata.nextLink") val nextLink: String? = null,
)
@Serializable data class GraphTableRow(val values: List<List<JsonElement>> = emptyList())
@Serializable data class GraphAddWorksheetRequest(val name: String)
@Serializable data class GraphRangeValuesRequest(val values: List<List<String>>)
@Serializable data class GraphAddTableRequest(val address: String, val hasHeaders: Boolean = true)
/**
 * No default values on purpose: the shared Json is configured with
 * encodeDefaults = false, which silently dropped the `folder` facet and the
 * conflict behaviour from the body — Graph then rejected the request (400).
 */
@Serializable data class GraphCreateFolderRequest(
    val name: String,
    val folder: Map<String, String>,
    @kotlinx.serialization.SerialName("@microsoft.graph.conflictBehavior") val conflictBehavior: String,
) {
    constructor(name: String) : this(name, emptyMap(), "fail")
}

/** Path helpers shared by the OneDrive image and Excel gateways. */
internal object OneDrivePaths {
    const val ROOT = "Shreddro"
    private const val GRAPH_ROOT = "https://graph.microsoft.com/v1.0/me/drive/root:"

    fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /** `…/root:/Shreddro/<bank>/<name>` (no trailing action). */
    fun itemUrl(bankKey: String, name: String): String =
        "$GRAPH_ROOT/${encode(ROOT)}/${encode(bankKey)}/${encode(name)}"

    /** `…/root:/Shreddro/<name>` — a file directly under the root folder. */
    fun rootItemUrl(name: String): String = "$GRAPH_ROOT/${encode(ROOT)}/${encode(name)}"

    fun rootUrl(): String = "$GRAPH_ROOT/${encode(ROOT)}"

    /** `…/root/children` — where the `Shreddro` root folder is created. */
    const val ROOT_CHILDREN_URL = "https://graph.microsoft.com/v1.0/me/drive/root/children"
}

/**
 * Excel Online ledger: ONE central workbook, `Shreddro/Shreddro
 * Transactions.xlsx` on the user's OneDrive, with a `bank_name` column.
 *
 * The workbook is created right after the root folder when an account is
 * linked, and only when the path does not exist (from a bundled empty .xlsx
 * whose single sheet is named "Slips"); the "Slips" table with its header
 * row is added once; each slip is one appended table row.
 */
class GraphExcelGateway(
    private val api: GraphApi,
    private val auth: AppAuthManager,
    /** Bytes of the bundled empty workbook (assets/empty_workbook.xlsx). */
    private val emptyWorkbook: () -> ByteArray,
    /** Called with the workbook's webUrl so the UI can deep-link to Excel. */
    private val onWorkbookUrl: (String) -> Unit = {},
    /** Called with the root `Shreddro` folder webUrl. */
    private val onFolderUrl: (String) -> Unit = {},
) : SpreadsheetGateway, CloudProvisioner, CloudLedger {

    override val provider = CloudProvider.MICROSOFT
    @Volatile private var workbookId: String? = null
    @Volatile private var headerWritten = false

    /**
     * Keys of every row in the "Slips" table. An empty set when the table has
     * not been created yet (fresh workbook) — nothing can be missing from it
     * that the first [appendRow] will not create.
     */
    override suspend fun existingKeys(): Set<String> {
        val bearer = "Bearer ${auth.freshAccessToken(CloudProvider.MICROSOFT)}"
        val itemId = resolveWorkbook(bearer)
        val keys = HashSet<String>()
        var page = api.listTableRows(bearer, itemId, TABLE)
        if (page.code() == 404) return emptySet()
        while (true) {
            if (!page.isSuccessful) throw HttpException(page)
            val body = page.body() ?: break
            for (row in body.value) {
                for (cells in row.values) keys += CloudRows.toRecord(cells.map(CloudRows::cellText)).key
            }
            val next = body.nextLink?.takeIf { it.isNotBlank() } ?: break
            page = api.listTableRowsByUrl(next, bearer)
        }
        return keys
    }

    override suspend fun append(record: LedgerRecord) = appendRow(record.toSlip(), record.imageFile)

    override suspend fun provision(bankKeys: Collection<String>) {
        val bearer = "Bearer ${auth.freshAccessToken(CloudProvider.MICROSOFT)}"
        val root = api.createFolder(
            OneDrivePaths.ROOT_CHILDREN_URL, bearer, GraphCreateFolderRequest(OneDrivePaths.ROOT),
        )
        val rootItem = when {
            root.isSuccessful -> root.body()
            root.code() == 409 -> api.getItemByUrl(OneDrivePaths.rootUrl() + "?\$select=id,webUrl", bearer).body()
            else -> throw HttpException(root)
        }
        rootItem?.webUrl?.takeIf { it.isNotBlank() }?.let(onFolderUrl)
        for (bank in bankKeys) ensureBankFolder(bearer, bank)
        ensureTable(bearer, resolveWorkbook(bearer))
        Log.d(TAG, "Excel: provisioned root, ${bankKeys.size} bank folder(s), central workbook")
    }

    private suspend fun ensureBankFolder(bearer: String, bankKey: String) {
        val resp = api.createFolder(
            OneDrivePaths.rootUrl() + ":/children", bearer, GraphCreateFolderRequest(bankKey),
        )
        if (!resp.isSuccessful && resp.code() != 409) throw HttpException(resp)
    }

    override suspend fun appendRow(slip: TransactionSlip, imageFileName: String) {
        val bearer = "Bearer ${auth.freshAccessToken(CloudProvider.MICROSOFT)}"
        val itemId = resolveWorkbook(bearer)
        ensureTable(bearer, itemId)

        val row = GraphRowsAddRequest(
            values = listOf(
                listOf(
                    JsonPrimitive(Instant.now().toString()),
                    JsonPrimitive(slip.bankName),
                    JsonPrimitive(slip.dateTime),
                    JsonPrimitive(slip.amount),
                    JsonPrimitive(slip.sender),
                    JsonPrimitive(slip.receiver),
                    JsonPrimitive(slip.referenceId),
                    JsonPrimitive(imageFileName),
                ),
            ),
        )
        val resp = api.addTableRow(bearer, itemId, TABLE, row)
        if (!resp.isSuccessful) throw HttpException(resp)
        Log.d(TAG, "Excel: appended ${slip.bankKey} row (${slip.amount})")
    }

    /** Finds `Shreddro/Shreddro Transactions.xlsx`, uploading the empty template if absent. */
    private suspend fun resolveWorkbook(bearer: String): String {
        workbookId?.let { return it }
        val name = WORKBOOK_NAME
        val lookup = api.getItemByUrl(OneDrivePaths.rootItemUrl(name) + "?\$select=id,webUrl", bearer)
        val item = when {
            lookup.isSuccessful -> lookup.body()
            lookup.code() == 404 -> {
                // PUT to a path creates intermediate folders; `fail` keeps a
                // concurrent creator's workbook instead of overwriting it.
                val created = api.uploadSmallFile(
                    OneDrivePaths.rootItemUrl(name) + ":/content?@microsoft.graph.conflictBehavior=fail",
                    bearer,
                    emptyWorkbook().toRequestBody(XLSX_MEDIA),
                )
                when {
                    created.isSuccessful -> created.body().also { Log.d(TAG, "Excel: created $name") }
                    created.code() == 409 -> api.getItemByUrl(
                        OneDrivePaths.rootItemUrl(name) + "?\$select=id,webUrl", bearer,
                    ).body()
                    else -> throw HttpException(created)
                }
            }
            else -> throw HttpException(lookup)
        }
        val id = item?.id ?: throw IllegalStateException("OneDrive returned no item id for $name")
        item.webUrl?.takeIf { it.isNotBlank() }?.let(onWorkbookUrl)
        workbookId = id
        return id
    }

    private suspend fun ensureTable(bearer: String, itemId: String) {
        val existing = api.getTable(bearer, itemId, TABLE)
        if (existing.isSuccessful) {
            // Table already there: still (re)write the header once per
            // process so workbooks created by older builds (header-less or
            // snake_case column names) get readable names. Editing the
            // header cells renames the table columns in place.
            if (!headerWritten) {
                val header = api.seedHeaderRow(bearer, itemId, SHEET, GraphRangeValuesRequest(listOf(LedgerColumns.HEADERS)))
                if (header.isSuccessful) headerWritten = true else Log.w(TAG, "Excel: header rewrite failed HTTP ${header.code()}")
            }
            return
        }
        if (existing.code() != 404) throw HttpException(existing)

        // The template already carries a "Slips" sheet; Graph answers 400
        // (not 409) to a duplicate add, so check first and only add when
        // the workbook really lacks it (e.g. a user-supplied file).
        val sheet = api.getWorksheet(bearer, itemId, SHEET)
        if (sheet.code() == 404) {
            val ws = api.addWorksheet(bearer, itemId, GraphAddWorksheetRequest(SHEET))
            if (!ws.isSuccessful) throw HttpException(ws)
        } else if (!sheet.isSuccessful) {
            throw HttpException(sheet)
        }

        val header = api.seedHeaderRow(bearer, itemId, SHEET, GraphRangeValuesRequest(listOf(LedgerColumns.HEADERS)))
        if (!header.isSuccessful) throw HttpException(header)
        headerWritten = true

        val created = api.addTable(bearer, itemId, GraphAddTableRequest(address = "$SHEET!A1:H1"))
        if (!created.isSuccessful && created.code() != 409) throw HttpException(created)
    }

    private companion object {
        const val TAG = "Shreddro.Upload"
        const val WORKBOOK_NAME = "Shreddro Transactions.xlsx"
        const val SHEET = "Slips"
        const val TABLE = "Slips"
        val XLSX_MEDIA =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".toMediaType()
    }
}

/**
 * OneDrive slip-image sync: `Shreddro/<bank>/<fileName>`. Folders are created
 * by the path-addressed PUT; an existing file of the same name is kept
 * (conflictBehavior=fail → 409 is treated as "already there").
 */
class OneDriveBinaryGateway(
    private val api: GraphApi,
    private val auth: AppAuthManager,
    /** Called with the `Shreddro` folder's webUrl so the UI can deep-link to OneDrive. */
    private val onFolderUrl: (String) -> Unit = {},
) : BinaryStorageGateway {

    override val provider = CloudProvider.MICROSOFT
    private var urlReported = false

    override suspend fun upload(bytes: ByteArray, fileName: String, bankKey: String) {
        val bearer = "Bearer ${auth.freshAccessToken(CloudProvider.MICROSOFT)}"
        val url = OneDrivePaths.itemUrl(bankKey, fileName) + ":/content?@microsoft.graph.conflictBehavior=fail"
        val resp = api.uploadSmallFile(url, bearer, bytes.toRequestBody(mimeTypeFor(fileName).toMediaType()))
        when {
            resp.isSuccessful -> Log.d(TAG, "OneDrive: uploaded $bankKey/$fileName (${bytes.size} B)")
            resp.code() == 409 -> Log.d(TAG, "OneDrive: $bankKey/$fileName already exists — skipped")
            else -> throw HttpException(resp)
        }

        if (!urlReported) {
            runCatching {
                api.getItemByUrl(OneDrivePaths.rootUrl() + "?\$select=webUrl", bearer).body()?.webUrl
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                urlReported = true
                onFolderUrl(it)
            }
        }
    }

    private companion object {
        const val TAG = "Shreddro.Upload"

        fun mimeTypeFor(fileName: String): String =
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "heic", "heif" -> "image/heic"
                else -> "image/jpeg"
            }
    }
}
