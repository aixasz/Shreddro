package com.shreddro.app.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shreddro.app.ocr.ImageDecoding
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.review.ReviewItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

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
 * Structured fields are picked, not typed: the bank comes from a dropdown
 * (with an "Other…" escape hatch) and the date/time from the M3 date + time
 * pickers, so what lands in the ledger is always in the canonical shape.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualEntryDialog(
    item: ReviewItem,
    onSave: (TransactionSlip) -> Unit,
    onCancel: () -> Unit,
) {
    var bankChoice by remember { mutableStateOf("") }
    var customBank by remember { mutableStateOf("") }
    var bankMenuOpen by remember { mutableStateOf(false) }
    // Defaults to now: most slips are entered the day they were received, so
    // the user usually only needs to nudge the time.
    var pickedDateTime by remember { mutableStateOf(LocalDateTime.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }
    var receiver by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }

    val bank = if (bankChoice == BANK_OTHER) customBank else bankChoice
    val dateTime = pickedDateTime.format(MANUAL_DATE_TIME_FORMAT)
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

                // Bank: fixed list so ledger rows group cleanly by bank; the
                // "Other…" entry reveals a free-text field for anything else.
                ExposedDropdownMenuBox(
                    expanded = bankMenuOpen,
                    onExpandedChange = { bankMenuOpen = it },
                ) {
                    OutlinedTextField(
                        value = bankChoice,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("Bank") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bankMenuOpen) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = bankMenuOpen,
                        onDismissRequest = { bankMenuOpen = false },
                    ) {
                        (BANK_OPTIONS + BANK_OTHER).forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    bankChoice = option
                                    bankMenuOpen = false
                                },
                            )
                        }
                    }
                }
                if (bankChoice == BANK_OTHER) {
                    OutlinedTextField(
                        value = customBank,
                        onValueChange = { customBank = it },
                        singleLine = true,
                        label = { Text("Bank name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Date/time: read-only field that opens the pickers. A readOnly
                // text field still swallows taps to place its cursor, so a
                // transparent clickable overlay catches the tap instead.
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = dateTime,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("Date/time") },
                        supportingText = { Text("Tap to pick date and time") },
                        trailingIcon = {
                            Icon(Icons.Default.DateRange, contentDescription = "Pick date and time")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .clickable { showDatePicker = true },
                    )
                }

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    singleLine = true,
                    label = { Text("Amount (THB)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = sender,
                    onValueChange = { sender = it },
                    singleLine = true,
                    label = { Text("Sender") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = receiver,
                    onValueChange = { receiver = it },
                    singleLine = true,
                    label = { Text("Receiver") },
                    modifier = Modifier.fillMaxWidth(),
                )
                // Bank references are Latin alphanumerics; the ASCII keyboard
                // skips the Thai layout the user is otherwise likely on.
                OutlinedTextField(
                    value = reference,
                    onValueChange = { reference = it },
                    singleLine = true,
                    label = { Text("Reference id") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
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

    // Date first, then time — two dialogs chained so each step gets the full
    // M3 picker. The picker states live inside the branches so every opening
    // starts from the current pick rather than the state of the first one.
    if (showDatePicker) {
        // DatePickerState works in UTC-midnight millis, so the round trip
        // must use UTC too or the day shifts for zones east of Greenwich.
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = pickedDateTime.toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = dateState.selectedDateMillis != null,
                    onClick = {
                        dateState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            pickedDateTime = LocalDateTime.of(date, pickedDateTime.toLocalTime())
                        }
                        showDatePicker = false
                        showTimePicker = true
                    },
                ) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }
    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = pickedDateTime.hour,
            initialMinute = pickedDateTime.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickedDateTime = LocalDateTime.of(
                            pickedDateTime.toLocalDate(),
                            LocalTime.of(timeState.hour, timeState.minute),
                        )
                        showTimePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
        )
    }
}

/** Bank dropdown options, most common Thai banks first. */
private val BANK_OPTIONS = listOf(
    "KBank", "Bangkok Bank", "Krungthai", "SCB", "Krungsri", "TTB", "GSB", "Paotang",
)
private const val BANK_OTHER = "Other…"

/**
 * Canonical manual-entry timestamp: "dd/MM/yyyy HH:mm", Gregorian, 24h.
 * Pinned to Locale.US so a Thai device locale can't swap in the Buddhist
 * calendar year (2569) or localized digits.
 */
private val MANUAL_DATE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.US)

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
