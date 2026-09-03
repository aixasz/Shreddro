package com.shreddro.app.net

import com.shreddro.core.gateway.SpreadsheetGateway
import com.shreddro.core.model.CloudProvider
import com.shreddro.core.model.TransactionSlip
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Google workflow: the app POSTs to the user's own Apps Script Web App
 * deployment (backend/apps-script/Code.gs). The script owns the spreadsheet,
 * creates a tab per bank_name, appends the row, and answers with JSON.
 *
 * Retrofit base URL = the deployment URL's origin; the full path is passed via
 * a dynamic @POST url because script.google.com exec URLs include the
 * deployment id.
 */
interface AppsScriptApi {
    @POST
    suspend fun append(
        @retrofit2.http.Url url: String,
        @Body payload: AppsScriptPayload,
    ): AppsScriptResponse
}

@Serializable
data class AppsScriptPayload(
    @SerialName("bank_name") val bankName: String,
    @SerialName("date_time") val dateTime: String,
    @SerialName("amount") val amount: Double,
    @SerialName("sender") val sender: String,
    @SerialName("receiver") val receiver: String,
    @SerialName("reference_id") val referenceId: String,
    /** Auth travels in the body — Apps Script cannot read custom headers. */
    @SerialName("secret") val secret: String,
)

@Serializable
data class AppsScriptResponse(
    val status: String,
    val sheet: String? = null,
    val row: Int? = null,
    val error: String? = null,
)

class AppsScriptSheetGateway(
    private val api: AppsScriptApi,
    private val deploymentUrl: String,
    private val sharedSecret: String,
) : SpreadsheetGateway {

    override val provider = CloudProvider.GOOGLE

    override suspend fun appendRow(slip: TransactionSlip) {
        val response = api.append(
            url = deploymentUrl,
            payload = AppsScriptPayload(
                bankName = slip.bankName,
                dateTime = slip.dateTime,
                amount = slip.amount,
                sender = slip.sender,
                receiver = slip.receiver,
                referenceId = slip.referenceId,
                secret = sharedSecret,
            ),
        )
        check(response.status == "ok") {
            "Apps Script gateway error: ${response.error ?: response.status}"
        }
    }
}
