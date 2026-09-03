package com.shreddro.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.shreddro.app.data.AppSettings

/**
 * Bring-your-own-credentials settings. These are runtime values entered by
 * the user and held in encrypted prefs — never compiled into the public APK.
 */
@Composable
fun SettingsDialog(
    settings: AppSettings,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    var gemini by remember { mutableStateOf(settings.geminiApiKey) }
    var scriptUrl by remember { mutableStateOf(settings.appsScriptUrl) }
    var scriptSecret by remember { mutableStateOf(settings.appsScriptSecret) }
    var workbookId by remember { mutableStateOf(settings.msWorkbookItemId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cloud & AI settings") },
        text = {
            Column {
                OutlinedTextField(
                    gemini, { gemini = it },
                    label = { Text("Gemini API key (AI slip reading)") },
                )
                OutlinedTextField(
                    scriptUrl, { scriptUrl = it },
                    label = { Text("Apps Script Web App URL (Google Sheet)") },
                )
                OutlinedTextField(
                    scriptSecret, { scriptSecret = it },
                    label = { Text("Apps Script shared secret") },
                )
                OutlinedTextField(
                    workbookId, { workbookId = it },
                    label = { Text("OneDrive workbook item id (Excel)") },
                )
                Text(
                    "Stored encrypted on this device only. Leave blank to " +
                        "disable that integration.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.geminiApiKey = gemini
                settings.appsScriptUrl = scriptUrl
                settings.appsScriptSecret = scriptSecret
                settings.msWorkbookItemId = workbookId
                onSaved()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
