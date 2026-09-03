package com.shreddro.app.auth

import android.content.Context
import android.content.Intent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.shreddro.core.model.CloudProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OIDC authorization-code + PKCE flow for both providers via AppAuth.
 * Tokens (incl. refresh tokens) are persisted in Keystore-encrypted prefs,
 * namespaced per provider so both ecosystems can be linked concurrently.
 */
class AppAuthManager(context: Context) {

    private val appContext = context.applicationContext
    private val service = AuthorizationService(appContext)

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "shreddro_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun linkedProviders(): Set<CloudProvider> = buildSet {
        if (authState(CloudProvider.GOOGLE)?.isAuthorized == true) add(CloudProvider.GOOGLE)
        if (authState(CloudProvider.MICROSOFT)?.isAuthorized == true) add(CloudProvider.MICROSOFT)
    }

    /** Builds the browser Intent that starts the consent flow. */
    fun authorizationIntent(provider: CloudProvider): Intent {
        val request = when (provider) {
            CloudProvider.GOOGLE -> AuthorizationRequest.Builder(
                AuthConfig.GOOGLE_SERVICE_CONFIG,
                AuthConfig.googleClientId,
                ResponseTypeValues.CODE,
                android.net.Uri.parse(AuthConfig.GOOGLE_REDIRECT_URI),
            ).setScopes(AuthConfig.GOOGLE_SCOPES).build()

            CloudProvider.MICROSOFT -> AuthorizationRequest.Builder(
                AuthConfig.MS_SERVICE_CONFIG,
                AuthConfig.msClientId,
                ResponseTypeValues.CODE,
                android.net.Uri.parse(AuthConfig.MS_REDIRECT_URI),
            ).setScopes(AuthConfig.MS_SCOPES).build()

            CloudProvider.LOCAL_CSV -> error("LOCAL_CSV requires no authorization")
        }
        return service.getAuthorizationRequestIntent(request)
    }

    /** Completes the flow from the redirect result and persists the AuthState. */
    suspend fun handleAuthorizationResponse(provider: CloudProvider, data: Intent) {
        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)
        val state = AuthState(response, exception)
        response ?: throw exception ?: IllegalStateException("Empty authorization result")

        suspendCancellableCoroutine { cont ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { tokenResp, tokenEx ->
                state.update(tokenResp, tokenEx)
                if (tokenResp != null) {
                    persist(provider, state)
                    cont.resume(Unit)
                } else {
                    cont.resumeWithException(tokenEx ?: IllegalStateException("Token exchange failed"))
                }
            }
        }
    }

    /** Returns a fresh (auto-refreshed) access token for API calls. */
    suspend fun freshAccessToken(provider: CloudProvider): String {
        val state = authState(provider) ?: throw IllegalStateException("$provider not linked")
        return suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(service) { accessToken, _, ex ->
                if (accessToken != null) {
                    persist(provider, state) // refresh may have rotated tokens
                    cont.resume(accessToken)
                } else {
                    cont.resumeWithException(ex ?: IllegalStateException("Token refresh failed"))
                }
            }
        }
    }

    fun unlink(provider: CloudProvider) {
        prefs.edit().remove(key(provider)).apply()
    }

    private fun authState(provider: CloudProvider): AuthState? =
        prefs.getString(key(provider), null)?.let { AuthState.jsonDeserialize(it) }

    private fun persist(provider: CloudProvider, state: AuthState) {
        prefs.edit().putString(key(provider), state.jsonSerializeString()).apply()
    }

    private fun key(provider: CloudProvider) = "auth_state_${provider.name}"

    fun dispose() = service.dispose()
}
