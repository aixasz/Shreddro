package com.shreddro.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shreddro.app.data.AppSettings

/**
 * Bring-your-own-credentials settings. These are runtime values entered by
 * the user and held in encrypted prefs — never compiled into the public APK.
 */
@Composable
fun SettingsDialog(
    settings: AppSettings,
    onSaved: () -> Unit,
    onRescanAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var scriptUrl by remember { mutableStateOf(settings.appsScriptUrl) }
    var scriptSecret by remember { mutableStateOf(settings.appsScriptSecret) }
    var workbookId by remember { mutableStateOf(settings.msWorkbookItemId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cloud sync settings") },
        text = {
            Column {
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
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(
                    "Gallery scan",
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Scans normally only look at photos newer than the last " +
                        "scan. Rescan to revisit every photo; slips already " +
                        "handled are skipped automatically.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onRescanAll) { Text("Rescan all photos") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.appsScriptUrl = scriptUrl
                settings.appsScriptSecret = scriptSecret
                settings.msWorkbookItemId = workbookId
                onSaved()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
