package com.shreddro.app.auth

import android.net.Uri
import com.shreddro.app.BuildConfig
import net.openid.appauth.AuthorizationServiceConfiguration

/**
 * OAuth 2.0 / OIDC endpoint + scope configuration for both identity ecosystems.
 *
 * Console setup (documented here so it lives next to the code that uses it):
 *
 * ── Google Cloud Console ─────────────────────────────────────────────────────
 *  1. Create an OAuth client of type "Android"; register package
 *     `com.shreddro.app` + your signing SHA-1.
 *  2. Enable the Google Drive API. (Sheets API not required on-device — sheet
 *     writes go through the user's own Apps Script Web App deployment.)
 *  3. Redirect URI: `com.shreddro.app:/oauth2redirect` (custom scheme; the
 *     scheme is set in build.gradle manifestPlaceholders).
 *  4. OAuth consent screen scopes: openid, email, profile, drive.file.
 *
 * ── Microsoft Entra ID (portal.azure.com) ────────────────────────────────────
 *  1. App registration → Supported accounts: "Personal Microsoft accounts and
 *     any organizational directory" (covers Office 365 + Live accounts).
 *  2. Add an Android platform: package `com.shreddro.app` + signature hash →
 *     the portal generates `msauth://com.shreddro.app/<base64hash>` — mirror it
 *     in AndroidManifest.xml and in [MS_REDIRECT_URI].
 *  3. API permissions (delegated): User.Read, Files.ReadWrite, offline_access.
 */
object AuthConfig {

    // ── Google ──
    val GOOGLE_SERVICE_CONFIG = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token"),
    )
    const val GOOGLE_CLIENT_ID_PLACEHOLDER = "shreddro.googleClientId in local.properties"
    val googleClientId: String get() = BuildConfig.GOOGLE_OAUTH_CLIENT_ID
    const val GOOGLE_REDIRECT_URI = "com.shreddro.app:/oauth2redirect"
    val GOOGLE_SCOPES = listOf(
        "openid", "email", "profile",
        // drive.file: access only to files/folders Shreddro itself creates.
        "https://www.googleapis.com/auth/drive.file",
    )

    // ── Microsoft (v2.0 endpoint, 'common' tenant = work/school + personal) ──
    val MS_SERVICE_CONFIG = AuthorizationServiceConfiguration(
        Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"),
        Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/token"),
    )
    val msClientId: String get() = BuildConfig.MS_OAUTH_CLIENT_ID
    // Debug-keystore signature hash; add the release keystore's hash to the
    // Entra app registration (and here) before shipping signed releases.
    const val MS_REDIRECT_URI = "msauth://com.shreddro.app/mdiqEPddjqIocm44yXlmGKnFWqU%3D"
    val MS_SCOPES = listOf(
        "openid", "profile", "offline_access",
        "User.Read", "Files.ReadWrite",
    )
}
