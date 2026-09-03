package com.shreddro.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.review.ReviewItem

/**
 * Minimal NeedsReview list: slips the LLM could not parse. Each row offers
 * Retry (fresh LLM attempt), Enter manually (contract fields form), Dismiss.
 */
@Composable
fun ReviewSection(
    items: List<ReviewItem>,
    onRetry: (ReviewItem) -> Unit,
    onManual: (ReviewItem, TransactionSlip) -> Unit,
    onDismiss: (ReviewItem) -> Unit,
) {
    if (items.isEmpty()) return
    var manualFor by remember { mutableStateOf<ReviewItem?>(null) }

    Column(Modifier.padding(top = 16.dp)) {
        Text("Needs review (${items.size})")
        items.forEach { item ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text(item.fileName, Modifier.padding(end = 8.dp))
                TextButton(onClick = { onRetry(item) }) { Text("Retry") }
                TextButton(onClick = { manualFor = item }) { Text("Enter") }
                TextButton(onClick = { onDismiss(item) }) { Text("Dismiss") }
            }
        }
    }

    manualFor?.let { item ->
        ManualEntryDialog(
            item = item,
            onSave = { slip ->
                manualFor = null
                onManual(item, slip)
            },
            onCancel = { manualFor = null },
        )
    }
}

@Composable
private fun ManualEntryDialog(
    item: ReviewItem,
    onSave: (TransactionSlip) -> Unit,
    onCancel: () -> Unit,
) {
    var bank by remember { mutableStateOf("") }
    var dateTime by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }
    var receiver by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }

    val parsedAmount = amount.toDoubleOrNull()
    val valid = bank.isNotBlank() && dateTime.isNotBlank() &&
        parsedAmount != null && parsedAmount >= 0.0

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(item.fileName) },
        text = {
            Column {
                OutlinedTextField(bank, { bank = it }, label = { Text("Bank name") })
                OutlinedTextField(dateTime, { dateTime = it }, label = { Text("Date/time") })
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount (THB)") })
                OutlinedTextField(sender, { sender = it }, label = { Text("Sender") })
                OutlinedTextField(receiver, { receiver = it }, label = { Text("Receiver") })
                OutlinedTextField(reference, { reference = it }, label = { Text("Reference id") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        TransactionSlip(
                            bankName = bank.trim(),
                            dateTime = dateTime.trim(),
                            amount = parsedAmount ?: 0.0,
                            sender = sender.trim(),
                            receiver = receiver.trim(),
                            referenceId = reference.trim(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
