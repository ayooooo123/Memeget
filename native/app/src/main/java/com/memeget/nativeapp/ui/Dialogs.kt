package com.memeget.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TagDialog(
    initialLabel: String = "",
    targetCount: Int = 1,
    allowSpread: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (label: String, category: String, spread: Boolean) -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var category by remember { mutableStateOf("topic") }
    var spread by remember { mutableStateOf(false) }

    val categories = listOf("topic", "character", "format", "emotion")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (targetCount > 1) "Tag $targetCount Memes" else "Add Manual Tag",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Tag Label") },
                    placeholder = { Text("e.g. pepe, surprised, reaction") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MemegetColors.Volt,
                        focusedLabelColor = MemegetColors.Volt,
                        cursorColor = MemegetColors.Volt,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelMedium,
                    color = MemegetColors.TextSecondary,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { cat ->
                        val selected = category == cat
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MemegetColors.VoltContainer else MemegetColors.SurfaceVariant
                                )
                                .clickable { category = cat }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = cat,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MemegetColors.Volt else MemegetColors.TextSecondary,
                            )
                        }
                    }
                }

                if (allowSpread) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { spread = !spread }
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = spread,
                            onCheckedChange = { spread = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MemegetColors.Volt,
                                checkmarkColor = MemegetColors.Background,
                            ),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Spread tag to visually similar memes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MemegetColors.TextSecondary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = label.trim()
                    if (trimmed.isNotEmpty()) {
                        onConfirm(trimmed, category, spread)
                    }
                },
                enabled = label.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MemegetColors.Volt,
                    contentColor = MemegetColors.Background,
                ),
            ) {
                Text("Apply Tag", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MemegetColors.TextSecondary)
            }
        },
        containerColor = MemegetColors.Surface,
    )
}

@Composable
internal fun TeachDialog(
    initialLabel: String = "",
    onDismiss: () -> Unit,
    onConfirm: (label: String, category: String, positive: Boolean, associations: List<String>) -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var category by remember { mutableStateOf("topic") }
    var isPositive by remember { mutableStateOf(true) }
    var associationsText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Teach Classifier Exemplar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label / Concept") },
                    placeholder = { Text("e.g. doge, wojak, galaxy-brain") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MemegetColors.Volt,
                        focusedLabelColor = MemegetColors.Volt,
                        cursorColor = MemegetColors.Volt,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "Exemplar Role",
                    style = MaterialTheme.typography.labelMedium,
                    color = MemegetColors.TextSecondary,
                )

                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isPositive,
                                onClick = { isPositive = true },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isPositive,
                            onClick = { isPositive = true },
                            colors = RadioButtonDefaults.colors(selectedColor = MemegetColors.Volt),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Positive Exemplar",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MemegetColors.TextPrimary,
                            )
                            Text(
                                text = "Pulls visually similar memes into this label",
                                style = MaterialTheme.typography.bodySmall,
                                color = MemegetColors.TextSecondary,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = !isPositive,
                                onClick = { isPositive = false },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = !isPositive,
                            onClick = { isPositive = false },
                            colors = RadioButtonDefaults.colors(selectedColor = MemegetColors.Danger),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Negative Exemplar (False Match)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MemegetColors.Danger,
                            )
                            Text(
                                text = "Vetoes matches across ALL classifier branches",
                                style = MaterialTheme.typography.bodySmall,
                                color = MemegetColors.TextSecondary,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = associationsText,
                    onValueChange = { associationsText = it },
                    label = { Text("Associated Terms (Optional)") },
                    placeholder = { Text("comma-separated terms") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MemegetColors.Volt,
                        focusedLabelColor = MemegetColors.Volt,
                        cursorColor = MemegetColors.Volt,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = label.trim()
                    if (trimmed.isNotEmpty()) {
                        val associations = associationsText.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        onConfirm(trimmed, category, isPositive, associations)
                    }
                },
                enabled = label.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPositive) MemegetColors.Volt else MemegetColors.Danger,
                    contentColor = if (isPositive) MemegetColors.Background else MemegetColors.TextPrimary,
                ),
            ) {
                Text(
                    text = if (isPositive) "Teach Positive" else "Teach Negative (Veto)",
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MemegetColors.TextSecondary)
            }
        },
        containerColor = MemegetColors.Surface,
    )
}

@Composable
internal fun DeleteConfirmationDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirmDeleteIndex: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (count > 1) "Remove $count Memes from Index?" else "Remove Meme from Index?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MemegetColors.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "This removes the selected item(s) from Memeget's search index and library display.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MemegetColors.TextPrimary,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MemegetColors.SurfaceVariant)
                        .padding(10.dp),
                ) {
                    Text(
                        text = "Safety Note: Your original files in your storage folders will NOT be deleted. Memeget does not perform destructive file deletion to prevent accidental loss.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MemegetColors.TextSecondary,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmDeleteIndex,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MemegetColors.Danger,
                    contentColor = MemegetColors.TextPrimary,
                ),
            ) {
                Text("Remove from Index", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MemegetColors.TextSecondary)
            }
        },
        containerColor = MemegetColors.Surface,
    )
}

@Composable
internal fun ImportUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String) -> Unit,
) {
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Import Meme from Link",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Paste a link to an image, video, X/Twitter post, or Tenor GIF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MemegetColors.TextSecondary,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL / Link") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MemegetColors.Volt,
                        focusedLabelColor = MemegetColors.Volt,
                        cursorColor = MemegetColors.Volt,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = url.trim()
                    if (trimmed.isNotEmpty()) {
                        onConfirm(trimmed)
                    }
                },
                enabled = url.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MemegetColors.Volt,
                    contentColor = MemegetColors.Background,
                ),
            ) {
                Text("Import Link", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MemegetColors.TextSecondary)
            }
        },
        containerColor = MemegetColors.Surface,
    )
}

@Composable
internal fun ConfirmationDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    isDanger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isDanger) MemegetColors.Danger else MemegetColors.TextPrimary,
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MemegetColors.TextSecondary,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDanger) MemegetColors.Danger else MemegetColors.Volt,
                    contentColor = if (isDanger) MemegetColors.TextPrimary else MemegetColors.Background,
                ),
            ) {
                Text(confirmText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MemegetColors.TextSecondary)
            }
        },
        containerColor = MemegetColors.Surface,
    )
}
