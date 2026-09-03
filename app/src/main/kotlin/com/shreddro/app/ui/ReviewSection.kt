package com.shreddro.app.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shreddro.app.ocr.ImageDecoding
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.review.ReviewItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NeedsReview screen: slips that passed detection but couldn't be read with
 * confidence. Each card offers Retry (fresh OCR pass), Enter manually
 * (contract-field form), or Dismiss. Originals stay in the gallery until
 * resolved — never guessed, never silently dropped.
 */
@Composable
fun ReviewScreen(
    items: List<ReviewItem>,
    onRetry: (ReviewItem) -> Unit,
    onRetryAll: () -> Unit,
    onManual: (ReviewItem, TransactionSlip) -> Unit,
    onDismiss: (ReviewItem) -> Unit,
    retrying: Boolean = false,
) {
    var manualFor by remember { mutableStateOf<ReviewItem?>(null) }

    if (items.isEmpty()) {
        EmptyState("Nothing needs review", "Slips that can't be read automatically will appear here.")
        return
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.padding(top = 16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Needs review",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        "${items.size}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                // One pass over every parked slip — the natural move after an
                // app update improves detection or OCR. Archives are swept in
                // a single consent dialog at the end.
                TextButton(onClick = onRetryAll, enabled = !retrying) {
                    Text(if (retrying) "Retrying…" else "Retry all")
                }
            }
        }
        item {
            Text(
                "These slips passed detection but couldn't be read automatically. " +
                    "They stay in your gallery until resolved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(items) { item ->
            ReviewCard(
                item = item,
                onRetry = { onRetry(item) },
                onManual = { manualFor = item },
                onDismiss = { onDismiss(item) },
            )
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
private fun ReviewCard(
    item: ReviewItem,
    onRetry: () -> Unit,
    onManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            SlipPreview(
                mediaId = item.mediaId,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(bottom = 12.dp),
            )
            Text(
                item.fileName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                item.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                ) { Text("Retry") }
                OutlinedButton(
                    onClick = onManual,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(44.dp),
                ) { Text("Type it in", maxLines = 1) }
                TextButton(onClick = onDismiss, modifier = Modifier.height(44.dp)) { Text("✕") }
            }
        }
    }
}

/**
 * Bring-your-own-data manual entry, matching the slip contract exactly.
 */
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
            // The slip itself sits above the form so the user can read the
            // fields off the image while typing; the column scrolls because
            // preview + six fields overflow small screens / landscape.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SlipPreview(
                    mediaId = item.mediaId,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .padding(bottom = 12.dp),
                )
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

/**
 * Downsampled render of the original gallery image behind a review item.
 * Review originals are never purged, so the MediaStore URI is still valid;
 * decoding goes through [ImageDecoding] so a card never holds more than a
 * ~1k-px bitmap. Shows a quiet placeholder if the image has gone missing.
 */
@Composable
private fun SlipPreview(mediaId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, mediaId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(mediaId))
                    ?.use { it.readBytes() }
                    ?.let { ImageDecoding.decodeScaled(it, PREVIEW_MAX_SIDE_PX) }
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "Slip image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Image unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val PREVIEW_MAX_SIDE_PX = 1024
