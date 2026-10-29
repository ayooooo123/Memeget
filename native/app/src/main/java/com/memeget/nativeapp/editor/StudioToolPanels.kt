package com.memeget.nativeapp.editor

import android.content.Context
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memeget.nativeapp.InferenceEngine
import com.memeget.nativeapp.TextRegion
import expo.modules.memegetbg.SubjectCutoutResult
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.max

/**
 * Pictures and music picked in the editor, copied into app storage: a draft lasts up to a week, and a picker's
 * permission to read the original does not.
 */
internal object EditorAssetStorage {
    fun copyAssetToEditorStorage(context: Context, uri: Uri): Uri {
        val dir = File(context.filesDir, "editor_assets")
        if (!dir.exists()) dir.mkdirs()
        val ext = getFileExtension(context, uri)
        val targetFile = File(dir, "asset_${UUID.randomUUID()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Could not open picked asset: $uri")
        return Uri.fromFile(targetFile)
    }

    fun cleanExpiredAssets(context: Context) {
        val dir = File(context.filesDir, "editor_assets")
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - DraftJournalStore.DRAFT_EXPIRY_MS - 24L * 60L * 60L * 1000L
        val old = dir.listFiles()?.filter { it.lastModified() < cutoff }.orEmpty()
        if (old.isEmpty()) return
        // A draft kept in use for more than a week can still name an asset picked at its start. Names are random,
        // so finding one in a draft's text means that draft uses it.
        val drafts = File(context.cacheDir, "meme_drafts").walkTopDown()
            .filter { it.isFile && it.name.startsWith("slot_") }
            .joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        old.forEach { if (!drafts.contains(it.name)) it.delete() }
    }

    private fun getFileExtension(context: Context, uri: Uri): String {
        var ext: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (name != null && name.contains(".")) {
                        ext = name.substringAfterLast('.').lowercase()
                    }
                }
            }
        } catch (_: Exception) {}
        if (ext.isNullOrBlank()) {
            val type = context.contentResolver.getType(uri)
            if (type != null) {
                ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
            }
        }
        if (ext.isNullOrBlank()) {
            val path = uri.path
            if (path != null && path.contains(".")) {
                ext = path.substringAfterLast('.').lowercase()
            }
        }
        return ext?.ifBlank { "bin" } ?: "bin"
    }
}
@Composable
internal fun TextInspectorPanel(
    selectedLayer: MemeEditLayer.Text?,
    onUpdateLayer: (MemeEditLayer.Text) -> Unit,
    onAddTextLayer: () -> Unit,
    onDuplicateLayer: () -> Unit,
    onDeleteLayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selectedLayer == null) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("No text layer selected", color = Color(0xFF8B949E), fontSize = 14.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onAddTextLayer,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD8FF4A))
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Text Layer", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Presets Row
        Text("PRESETS", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val presets = listOf("impact", "subtitle", "label", "news", "bubble", "plain")
            for (p in presets) {
                val selected = selectedLayer.style.preset == p
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color(0xFFD8FF4A) else Color(0xFF21262D))
                        .clickable { onUpdateLayer(TextLayoutBridge.applyPreset(selectedLayer, p)) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        p.uppercase(),
                        color = if (selected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Text Input Field
        OutlinedTextField(
            value = selectedLayer.text,
            onValueChange = { onUpdateLayer(selectedLayer.copy(text = it)) },
            label = { Text("Meme Text") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFD8FF4A),
                unfocusedBorderColor = Color(0xFF30363D)
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Colors (Fill & Outline)
        Text("COLORS", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (hex in TextLayoutBridge.COLOR_SWATCHES) {
                val selected = selectedLayer.style.color.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(android.graphics.Color.parseColor(hex)))
                        .border(
                            if (selected) 2.5.dp else 1.dp,
                            if (selected) Color(0xFFD8FF4A) else Color(0xFF30363D),
                            CircleShape
                        )
                        .clickable { onUpdateLayer(selectedLayer.copy(style = selectedLayer.style.copy(color = hex))) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Outline Width Slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Outline", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(60.dp))
            Slider(
                value = selectedLayer.style.outlineScale,
                onValueChange = { onUpdateLayer(selectedLayer.copy(style = selectedLayer.style.copy(outlineScale = it))) },
                valueRange = 0f..0.5f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
            )
            Text("${(selectedLayer.style.outlineScale * 100).roundToInt()}%", color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.width(40.dp))
        }

        // Font Size Slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(60.dp))
            Slider(
                value = selectedLayer.fontSize,
                onValueChange = { onUpdateLayer(selectedLayer.copy(fontSize = it)) },
                valueRange = ProjectLimits.MIN_FONT_SIZE..ProjectLimits.MAX_FONT_SIZE,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
            )
            Text("${(selectedLayer.fontSize * 100).roundToInt()}%", color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.width(40.dp))
        }

        // Text Width Slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Width", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(60.dp))
            Slider(
                value = selectedLayer.width,
                onValueChange = { onUpdateLayer(selectedLayer.copy(width = it)) },
                valueRange = ProjectLimits.MIN_TEXT_WRAP_WIDTH..ProjectLimits.MAX_TEXT_WRAP_WIDTH,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
            )
            Text("${(selectedLayer.width * 100).roundToInt()}%", color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.width(40.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Alignment & Case
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("left" to Icons.Default.FormatAlignLeft, "center" to Icons.Default.FormatAlignCenter, "right" to Icons.Default.FormatAlignRight).forEach { (align, icon) ->
                    val selected = selectedLayer.style.align == align
                    IconButton(
                        onClick = { onUpdateLayer(selectedLayer.copy(style = selectedLayer.style.copy(align = align))) },
                        modifier = Modifier
                            .size(36.dp)
                            .background(if (selected) Color(0xFF21262D) else Color.Transparent, RoundedCornerShape(6.dp))
                    ) {
                        Icon(icon, contentDescription = align, tint = if (selected) Color(0xFFD8FF4A) else Color.White)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ALL CAPS", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = selectedLayer.style.uppercase,
                    onCheckedChange = { onUpdateLayer(selectedLayer.copy(style = selectedLayer.style.copy(uppercase = it))) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color(0xFFD8FF4A))
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Backing Toggle & Colors
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Backing", color = Color.White, fontSize = 13.sp)
            Switch(
                checked = selectedLayer.style.backgroundColor != null,
                onCheckedChange = { enabled ->
                    val defaultBg = TextLayoutBridge.getPresetSpec(selectedLayer.style.preset).style.backgroundColor ?: "#0a0b0e"
                    onUpdateLayer(selectedLayer.copy(style = selectedLayer.style.copy(backgroundColor = if (enabled) defaultBg else null)))
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color(0xFFD8FF4A))
            )
        }

        if (selectedLayer.style.backgroundColor != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text("BACKING COLOR", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (hex in TextLayoutBridge.COLOR_SWATCHES) {
                    val selected = selectedLayer.style.backgroundColor.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(android.graphics.Color.parseColor(hex)))
                            .border(
                                if (selected) 2.5.dp else 1.dp,
                                if (selected) Color(0xFFD8FF4A) else Color(0xFF30363D),
                                CircleShape
                            )
                            .clickable { onUpdateLayer(selectedLayer.copy(style = selectedLayer.style.copy(backgroundColor = hex))) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Opacity Slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Opacity", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(60.dp))
            Slider(
                value = selectedLayer.style.opacity,
                onValueChange = { onUpdateLayer(selectedLayer.copy(style = selectedLayer.style.copy(opacity = it))) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
            )
            Text("${(selectedLayer.style.opacity * 100).roundToInt()}%", color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.width(40.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Quick Slot Placement (Top, Middle, Bottom)
        Text("SNAP POSITION", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("top" to "Top", "middle" to "Center", "bottom" to "Bottom").forEach { (slot, title) ->
                OutlinedButton(
                    onClick = {
                        val pt = ProjectCore.captionSlotCenter(slot)
                        val updatedKeyframes = selectedLayer.keyframes.map { it.copy(center = pt) }
                        onUpdateLayer(selectedLayer.copy(keyframes = updatedKeyframes))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text(title, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Actions: Duplicate, Delete
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onDuplicateLayer,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Duplicate")
            }

            OutlinedButton(
                onClick = onDeleteLayer,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Delete")
            }
        }
    }
}

@Composable
internal fun TransformInspectorPanel(
    base: BaseTransform,
    sourceWidth: Int = 1000,
    sourceHeight: Int = 1000,
    onUpdateBase: (BaseTransform) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("CROP", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Drag a corner to resize, drag inside the frame to move it. The dimmed area is cut on export.",
            color = Color(0xFF8B949E),
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text("ASPECT RATIO", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val aspects = listOf("source", "1:1", "4:5", "9:16", "16:9", "free")
            for (asp in aspects) {
                val selected = base.outputAspect == asp
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color(0xFFD8FF4A) else Color(0xFF21262D))
                        .clickable {
                            onUpdateBase(ProjectCore.applyCropPreset(base, asp, sourceWidth, sourceHeight))
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        asp.uppercase(),
                        color = if (selected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("ORIENTATION & FLIP", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onUpdateBase(base.copy(rotation = base.rotation.next())) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Rotate 90°")
            }

            OutlinedButton(
                onClick = { onUpdateBase(base.copy(flipX = !base.flipX)) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (base.flipX) Color(0xFF21262D) else Color.Transparent,
                    contentColor = if (base.flipX) Color(0xFFD8FF4A) else Color.White
                )
            ) {
                Text("Flip H")
            }

            OutlinedButton(
                onClick = { onUpdateBase(base.copy(flipY = !base.flipY)) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (base.flipY) Color(0xFF21262D) else Color.Transparent,
                    contentColor = if (base.flipY) Color(0xFFD8FF4A) else Color.White
                )
            ) {
                Text("Flip V")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = {
                onUpdateBase(BaseTransform())
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8B949E))
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Reset Transform")
        }
    }
}

@Composable
internal fun DrawToolPanel(
    settings: DrawSettings,
    opacity: Float,
    onChangeSettings: (DrawSettings) -> Unit,
    onChangeOpacity: (Float) -> Unit,
    onUndoLast: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("SHAPE", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val shapes = listOf(
                Pair(DrawShape.FREE, "Freehand"),
                Pair(DrawShape.LINE, "Line"),
                Pair(DrawShape.ARROW, "Arrow"),
                Pair(DrawShape.RECTANGLE, "Rectangle"),
                Pair(DrawShape.ELLIPSE, "Ellipse")
            )
            for ((shape, name) in shapes) {
                val selected = settings.shape == shape
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color(0xFFD8FF4A) else Color(0xFF21262D))
                        .clickable { onChangeSettings(settings.copy(shape = shape)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        name,
                        color = if (selected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Stroke Width Presets
        Text("STROKE WIDTH", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for ((label, scale) in DrawToolCore.STROKE_PRESETS) {
                val selected = (settings.strokeScale - scale) in -0.001f..0.001f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Color(0xFFD8FF4A) else Color(0xFF21262D))
                        .clickable { onChangeSettings(settings.copy(strokeScale = scale)) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (selected) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Color Swatches
        Text("COLOR", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (hex in DrawToolCore.COLORS) {
                val selected = settings.color.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(android.graphics.Color.parseColor(hex)))
                        .border(
                            if (selected) 2.5.dp else 1.dp,
                            if (selected) Color(0xFFD8FF4A) else Color(0xFF30363D),
                            CircleShape
                        )
                        .clickable { onChangeSettings(settings.copy(color = hex)) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Fill toggle for shapes
        if (settings.shape == DrawShape.RECTANGLE || settings.shape == DrawShape.ELLIPSE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Fill Shape", color = Color.White, fontSize = 13.sp)
                Switch(
                    checked = settings.filled,
                    onCheckedChange = { onChangeSettings(settings.copy(filled = it)) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color(0xFFD8FF4A))
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Layer Opacity Slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Opacity", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(60.dp))
            Slider(
                value = opacity,
                onValueChange = onChangeOpacity,
                valueRange = 0.1f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
            )
            Text("${(opacity * 100).roundToInt()}%", color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.width(40.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onUndoLast,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("Undo Stroke")
            }
            OutlinedButton(
                onClick = onClearAll,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
            ) {
                Text("Clear All")
            }
        }
    }
}

@Composable
internal fun TextReplaceToolPanel(
    sourceUri: String,
    engine: InferenceEngine,
    base: BaseTransform = BaseTransform(),
    manualMode: Boolean,
    manualRegion: NormalizedRect,
    onToggleManualMode: (Boolean) -> Unit,
    onUpdateManualRegion: (NormalizedRect) -> Unit,
    onApplyCover: (NormalizedRect, String) -> Unit,
    onApplyPixelate: (NormalizedRect, Int) -> Unit,
    onApplyReplace: (NormalizedRect, String, String, ProjectCore.InferredTextStyle?) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var isDetecting by remember { mutableStateOf(false) }
    var detectedRegions by remember { mutableStateOf<List<TextRegion>>(emptyList()) }
    var selectedRegion by remember { mutableStateOf<TextRegion?>(null) }
    var replaceText by remember { mutableStateOf("") }
    var pixelSize by remember { mutableFloatStateOf(16f) }
    var coverColor by remember { mutableStateOf("#000000") }
    var inferredStyle by remember { mutableStateOf<ProjectCore.InferredTextStyle?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        isDetecting = true
                        errorMessage = null
                        try {
                            val regions = engine.recognize(sourceUri)
                            detectedRegions = regions
                            if (regions.isNotEmpty()) {
                                selectedRegion = regions.first()
                                if (replaceText.isBlank()) {
                                    replaceText = regions.first().text
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "Failed to recognize text"
                        } finally {
                            isDetecting = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isDetecting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD8FF4A))
            ) {
                if (isDetecting) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Detecting...", color = Color.Black)
                } else {
                    Text("Auto Detect Text", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }

            OutlinedButton(
                onClick = { onToggleManualMode(!manualMode) },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (manualMode) Color(0xFF21262D) else Color.Transparent,
                    contentColor = if (manualMode) Color(0xFFD8FF4A) else Color.White
                )
            ) {
                Text(if (manualMode) "Manual Box ON" else "Manual Box")
            }
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                color = Color(0xFFFF5252),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Detected Text Regions List
        if (detectedRegions.isNotEmpty() && !manualMode) {
            Text("DETECTED REGIONS", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (region in detectedRegions) {
                    val isSel = selectedRegion == region
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSel) Color(0xFF21262D) else Color(0xFF161B22))
                            .border(1.dp, if (isSel) Color(0xFFD8FF4A) else Color(0xFF30363D), RoundedCornerShape(6.dp))
                            .clickable {
                                selectedRegion = region
                                replaceText = region.text
                                val style = ProjectCore.inferOriginalTextStyle(
                                    sampledColors = listOf(coverColor, "#FFFFFF", "#000000", coverColor),
                                    originalText = region.text,
                                    regionHeight = region.height,
                                    lineCount = max(1, region.text.split("\n").size)
                                )
                                inferredStyle = style
                                coverColor = style.backgroundColor
                            }
                            .padding(8.dp)
                    ) {
                        Text(region.text.ifBlank { "[Region]" }, color = Color.White, fontSize = 13.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Active Target Rect mapped through base transform
        val activeRect = if (manualMode) manualRegion else selectedRegion?.let {
            ProjectCore.transformRectToWorkingCanvas(
                NormalizedRect(it.x, it.y, it.width, it.height),
                base
            )
        }

        if (activeRect != null) {
            Text("ACTION", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            // Replace Text Input
            OutlinedTextField(
                value = replaceText,
                onValueChange = { replaceText = it },
                label = { Text("Replacement Text") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFD8FF4A),
                    unfocusedBorderColor = Color(0xFF30363D)
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Cover Color Picker
            Text("COVER COLOR", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (hex in TextLayoutBridge.COLOR_SWATCHES) {
                    val isSel = coverColor.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(android.graphics.Color.parseColor(hex)))
                            .border(if (isSel) 2.dp else 1.dp, if (isSel) Color(0xFFD8FF4A) else Color(0xFF30363D), CircleShape)
                            .clickable { coverColor = hex }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Pixel Size Slider for Pixelate
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mosaic", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(60.dp))
                Slider(
                    value = pixelSize,
                    onValueChange = { pixelSize = it },
                    valueRange = 4f..64f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
                )
                Text("${pixelSize.roundToInt()}px", color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.width(44.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons: Cover, Pixelate, Replace
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onApplyCover(activeRect, coverColor) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                ) {
                    Text("Cover", color = Color.White)
                }

                Button(
                    onClick = { onApplyPixelate(activeRect, pixelSize.roundToInt()) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                ) {
                    Text("Pixelate", color = Color.White)
                }

                Button(
                    onClick = { onApplyReplace(activeRect, coverColor, replaceText, inferredStyle) },
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD8FF4A))
                ) {
                    Text("Replace", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
internal fun SubjectCutoutToolPanel(
    sourceUri: String,
    engine: InferenceEngine,
    backgroundSpec: BackgroundSpec,
    onUpdateBackground: (BackgroundSpec) -> Unit,
    onAddCutoutLayer: (SubjectCutoutResult, Int?, Float, Float, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var isSegmenting by remember { mutableStateOf(false) }
    var cutoutResult by remember { mutableStateOf<SubjectCutoutResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedSubjectIndex by remember { mutableStateOf<Int?>(null) }
    var outlineScale by remember { mutableFloatStateOf(0.35f) }
    var shadowScale by remember { mutableFloatStateOf(0.4f) }
    var duplicateSticker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Button(
            onClick = {
                scope.launch {
                    isSegmenting = true
                    errorMessage = null
                    try {
                        val reqId = "cutout_${System.currentTimeMillis()}"
                        val res = engine.segment(sourceUri, reqId)
                        cutoutResult = res
                        selectedSubjectIndex = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        errorMessage = error.message ?: "Subject isolation failed. Try again."
                    } finally {
                        isSegmenting = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSegmenting,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD8FF4A))
        ) {
            if (isSegmenting) {
                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Isolating Subject (FastSAM)...", color = Color.Black)
            } else {
                Text("Cut Out Subject", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
        errorMessage?.let {
            Text(it, color = Color(0xFFFF5252), modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Subject selector chips
        cutoutResult?.let { res ->
            if (res.combined == null) {
                Text("No subject found in this image.", color = Color(0xFF8B949E))
            }
            if (res.subjects.isNotEmpty()) {
                Text("SELECT SUBJECT", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isAll = selectedSubjectIndex == null
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isAll) Color(0xFFD8FF4A) else Color(0xFF21262D))
                            .clickable { selectedSubjectIndex = null }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("All Combined", color = if (isAll) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    for (i in res.subjects.indices) {
                        val isSel = selectedSubjectIndex == i
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) Color(0xFFD8FF4A) else Color(0xFF21262D))
                                .clickable { selectedSubjectIndex = i }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Subject #${i + 1}", color = if (isSel) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Background Mode Selector
        Text("BACKGROUND", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val bgModes = listOf(
                Pair("source", "Keep Source"),
                Pair("transparent", "Transparent"),
                Pair("solid", "Solid"),
                Pair("blurred-source", "Blurred Source"),
                Pair("image", "Image")
            )
            for ((mode, label) in bgModes) {
                val selected = backgroundSpec.mode == mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color(0xFFD8FF4A) else Color(0xFF21262D))
                        .clickable { onUpdateBackground(backgroundSpec.copy(mode = mode)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        label,
                        color = if (selected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (backgroundSpec.mode == "solid") {
            Spacer(modifier = Modifier.height(8.dp))
            Text("SOLID COLOR", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            val solidColors = listOf("#000000", "#FFFFFF", "#B8FF2C", "#FF4E42", "#20242A", "#E9E5DC")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (hex in solidColors) {
                    val selected = backgroundSpec.color.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(android.graphics.Color.parseColor(hex)))
                            .border(
                                if (selected) 2.5.dp else 1.dp,
                                if (selected) Color(0xFFD8FF4A) else Color(0xFF30363D),
                                CircleShape
                            )
                            .clickable { onUpdateBackground(backgroundSpec.copy(mode = "solid", color = hex)) }
                    )
                }
            }
        }

        val context = LocalContext.current
        val bgImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        val copiedUri = withContext(Dispatchers.IO) {
                            EditorAssetStorage.copyAssetToEditorStorage(context, uri)
                        }
                        onUpdateBackground(backgroundSpec.copy(mode = "image", assetUri = copiedUri.toString()))
                    } catch (e: Exception) {
                        errorMessage = "Failed to copy background image: ${e.message}"
                    }
                }
            }
        }

        if (backgroundSpec.mode == "image") {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { bgImagePicker.launch("image/*") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
            ) {
                Text(
                    if (backgroundSpec.assetUri.isNullOrBlank()) "Choose Background Image" else "Change Background Image",
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Sticker Effects (Outline & Shadow)
        Text("STICKER EFFECTS", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Outline", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(60.dp))
            Slider(
                value = outlineScale,
                onValueChange = { outlineScale = it },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
            )
            Text("${(outlineScale * 100).roundToInt()}%", color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.width(40.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Shadow", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(60.dp))
            Slider(
                value = shadowScale,
                onValueChange = { shadowScale = it },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
            )
            Text("${(shadowScale * 100).roundToInt()}%", color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.width(40.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Duplicate", color = Color.White, fontSize = 13.sp)
            Switch(
                checked = duplicateSticker,
                onCheckedChange = { duplicateSticker = it },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color(0xFFD8FF4A))
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        cutoutResult?.let { res ->
            Button(
                onClick = {
                    onAddCutoutLayer(res, selectedSubjectIndex, outlineScale, shadowScale, duplicateSticker)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3FB950))
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Cutout Sticker to Canvas", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun LayersListPanel(
    layers: List<MemeEditLayer>,
    selectedLayerId: String?,
    onSelectLayer: (String) -> Unit,
    onMoveLayer: (String, Int) -> Unit,
    onDuplicateLayer: (String) -> Unit,
    onDeleteLayer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (layers.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("No layers yet. Add text, drawings, or cutouts.", color = Color(0xFF8B949E), fontSize = 13.sp)
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(layers) { index, layer ->
            val isSelected = layer.id == selectedLayerId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Color(0xFF21262D) else Color(0xFF161B22))
                    .border(1.dp, if (isSelected) Color(0xFFD8FF4A) else Color(0xFF30363D), RoundedCornerShape(8.dp))
                    .clickable { onSelectLayer(layer.id) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when (layer) {
                            is MemeEditLayer.Text -> "Text: \"${layer.text}\""
                            is MemeEditLayer.Cover -> "Cover (${layer.mode})"
                            is MemeEditLayer.Subject -> "Subject Cutout"
                            is MemeEditLayer.Media -> "Media Sticker"
                            is MemeEditLayer.Draw -> "Drawing (${layer.elements.size} marks)"
                        },
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { if (index > 0) onMoveLayer(layer.id, index - 1) },
                        enabled = index > 0,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up", tint = if (index > 0) Color.White else Color(0xFF484F58))
                    }
                    IconButton(
                        onClick = { if (index < layers.size - 1) onMoveLayer(layer.id, index + 1) },
                        enabled = index < layers.size - 1,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down", tint = if (index < layers.size - 1) Color.White else Color(0xFF484F58))
                    }
                    IconButton(
                        onClick = { onDuplicateLayer(layer.id) },
                        enabled = layers.size < ProjectLimits.MAX_LAYERS,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate", tint = if (layers.size < ProjectLimits.MAX_LAYERS) Color.White else Color(0xFF484F58))
                    }
                    IconButton(onClick = { onDeleteLayer(layer.id) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoTimelinePanel(
    project: MemeEditProject,
    playheadUs: Long,
    onScrubPlayhead: (Long) -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onTrimRange: (Int, Long, Long) -> Unit,
    onRemoveSegment: (Int) -> Unit,
    onAddCardWithContent: (String, String, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val durationUs = project.source.durationUs ?: 1_000_000L
    val retainedRanges = project.video?.retainedRanges ?: listOf(TimeRangeUs(0L, durationUs))
    var showCardDialog by remember { mutableStateOf(false) }
    var cardText by remember { mutableStateOf("Title") }
    var cardBgColor by remember { mutableStateOf("#0A0B0E") }
    var cardDurationSec by remember { mutableFloatStateOf(1f) }

    if (showCardDialog) {
        AlertDialog(
            onDismissRequest = { showCardDialog = false },
            title = { Text("Add Title Card", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = cardText,
                        onValueChange = { cardText = it },
                        label = { Text("Title Card Text") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Duration", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(60.dp))
                        Slider(
                            value = cardDurationSec,
                            onValueChange = { cardDurationSec = it },
                            valueRange = 0.5f..5f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
                        )
                        Text(String.format("%.1fs", cardDurationSec), color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.width(40.dp))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCardDialog = false
                        onAddCardWithContent(cardText, cardBgColor, (cardDurationSec * 1_000_000L).toLong())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD8FF4A))
                ) {
                    Text("Add Card", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCardDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("TIMELINE SCRUB", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            val sec = (playheadUs / 1_000_000.0)
            val totalSec = (durationUs / 1_000_000.0)
            Text(String.format("%.2fs / %.2fs", sec, totalSec), color = Color(0xFFD8FF4A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Slider(
            value = playheadUs.toFloat(),
            onValueChange = { onScrubPlayhead(it.toLong()) },
            valueRange = 0f..durationUs.toFloat(),
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSplitAtPlayhead,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
            ) {
                Text("Split at Playhead", color = Color.White)
            }

            Button(
                onClick = { showCardDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
            ) {
                Text("Add Title Card", color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("RETAINED RANGES (${retainedRanges.size})", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        for ((idx, r) in retainedRanges.withIndex()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(Color(0xFF161B22), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        String.format("Seg %d: %.2fs - %.2fs", idx + 1, r.startUs / 1_000_000.0, r.endUs / 1_000_000.0),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (retainedRanges.size > 1) {
                        IconButton(onClick = { onRemoveSegment(idx) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove segment", tint = Color(0xFFFF5252))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val newStart = playheadUs.coerceIn(0L, r.endUs - 100_000L)
                            onTrimRange(idx, newStart, r.endUs)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Trim Start to Playhead", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val newEnd = playheadUs.coerceIn(r.startUs + 100_000L, durationUs)
                            onTrimRange(idx, r.startUs, newEnd)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Trim End to Playhead", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoFrameStripPanel(
    playheadUs: Long,
    durationUs: Long,
    sourceUri: String? = null,
    sourceFrameRate: Double? = null,
    onSeekFrame: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var indexState by remember(sourceUri) { mutableStateOf<FrameTimelineIndex?>(null) }
    var errorState by remember(sourceUri) { mutableStateOf<String?>(null) }
    var isLoading by remember(sourceUri) { mutableStateOf(!sourceUri.isNullOrBlank()) }

    LaunchedEffect(sourceUri) {
        if (sourceUri.isNullOrBlank()) {
            isLoading = false
            errorState = "No video source available"
            return@LaunchedEffect
        }
        isLoading = true
        errorState = null
        try {
            val idx = FrameTimelineIndex.load(context, sourceUri)
            indexState = idx
        } catch (e: Exception) {
            errorState = e.message ?: "Failed to index video frames"
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val index = indexState
        val err = errorState

        if (isLoading) {
            Text(
                "FRAME NAVIGATION",
                color = Color(0xFF8B949E),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            CircularProgressIndicator(
                color = Color(0xFFD8FF4A),
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Indexing frame timestamps from MediaExtractor PTS...",
                color = Color(0xFF8B949E),
                fontSize = 12.sp
            )
        } else if (err != null || index == null) {
            Text(
                "FRAME NAVIGATION",
                color = Color(0xFF8B949E),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Cannot claim exact frames: ${err ?: "Video track not indexed"}",
                color = Color(0xFFFF5252),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        } else {
            // Index is loaded from actual MediaExtractor PTS
            val title = if (index.isVariableFrameRate) "EXACT FRAME NAVIGATION (VFR)" else "EXACT FRAME NAVIGATION"
            Text(title, color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            val currentIdx = index.findFrameIndex(playheadUs)
            val currentFramePts = index.getTimestampUs(currentIdx)
            val totalFrames = index.frameCount
            val fpsInfo = if (index.isVariableFrameRate) {
                "VFR"
            } else if (index.nominalFps != null && index.nominalFps > 1.0) {
                String.format("%.1ffps", index.nominalFps)
            } else {
                "CFR"
            }

            Text(
                String.format("Frame #%d / %d  (%.3fs)  [%s]", currentIdx + 1, totalFrames, currentFramePts / 1_000_000.0, fpsInfo),
                color = Color(0xFFD8FF4A),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                String.format("%d μs", currentFramePts),
                color = Color(0xFF8B949E),
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            val prevUs = index.previousFrameTimeUs(playheadUs)
            val nextUs = index.nextFrameTimeUs(playheadUs)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { prevUs?.let { onSeekFrame(it) } },
                    enabled = prevUs != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF21262D),
                        disabledContainerColor = Color(0xFF161B22)
                    )
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = null,
                        tint = if (prevUs != null) Color.White else Color(0xFF484F58)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("-1 Frame", color = if (prevUs != null) Color.White else Color(0xFF484F58))
                }

                Button(
                    onClick = { nextUs?.let { onSeekFrame(it) } },
                    enabled = nextUs != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF21262D),
                        disabledContainerColor = Color(0xFF161B22)
                    )
                ) {
                    Text("+1 Frame", color = if (nextUs != null) Color.White else Color(0xFF484F58))
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = if (nextUs != null) Color.White else Color(0xFF484F58)
                    )
                }
            }
        }
    }
}


@Composable
internal fun VideoMotionToolPanel(
    selectedLayer: MemeEditLayer?,
    playheadUs: Long,
    durationUs: Long = 1_000_000L,
    onAddKeyframe: () -> Unit,
    onRemoveKeyframe: () -> Unit,
    onUpdateLayer: (MemeEditLayer) -> Unit,
    onSeekPlayhead: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (selectedLayer == null || (selectedLayer !is MemeEditLayer.Text && selectedLayer !is MemeEditLayer.Subject && selectedLayer !is MemeEditLayer.Media)) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Select a text, cutout, or media layer to edit its motion and timing.", color = Color(0xFF8B949E), fontSize = 13.sp)
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        val keyframes = when (selectedLayer) {
            is MemeEditLayer.Text -> selectedLayer.keyframes
            is MemeEditLayer.Subject -> selectedLayer.keyframes
            is MemeEditLayer.Media -> selectedLayer.keyframes
            else -> emptyList()
        }
        val prevKfUs = keyframes.filter { it.timeUs < playheadUs }.maxByOrNull { it.timeUs }?.timeUs
        val nextKfUs = keyframes.filter { it.timeUs > playheadUs }.minByOrNull { it.timeUs }?.timeUs
        // As in the original app, easing belongs to the keyframe under the playhead; elsewhere the chips are off.
        val targetKeyframe = keyframes.find { it.timeUs == playheadUs }
        val active = selectedLayer.active ?: TimeRangeUs(0L, durationUs)
        val copyTargetUs = nextKfUs ?: active.endUs
        val canCopyForward = copyTargetUs > playheadUs &&
            (selectedLayer.active == null || (playheadUs in active.startUs..active.endUs && copyTargetUs in active.startUs..active.endUs))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { prevKfUs?.let { onSeekPlayhead(it) } },
                modifier = Modifier.weight(1f),
                enabled = prevKfUs != null,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("◀ Prev keyframe")
            }

            OutlinedButton(
                onClick = { nextKfUs?.let { onSeekPlayhead(it) } },
                modifier = Modifier.weight(1f),
                enabled = nextKfUs != null,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("Next keyframe ▶")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("KEYFRAMES AT PLAYHEAD", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onAddKeyframe,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD8FF4A))
            ) {
                Text("Add Keyframe Here", color = Color.Black, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onRemoveKeyframe,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
            ) {
                Text("Delete Keyframe")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                onUpdateLayer(ProjectCore.copyKeyframeForward(selectedLayer, playheadUs, copyTargetUs))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = canCopyForward,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
        ) {
            Text("Copy forward", color = if (canCopyForward) Color.White else Color(0xFF8B949E))
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("KEYFRAME EASING", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        val easingOptions = listOf(
            "linear" to "Ease linear",
            "hold" to "Hold"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for ((wire, label) in easingOptions) {
                val selected = targetKeyframe?.easing == wire
                val enabled = targetKeyframe != null
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color(0xFFD8FF4A) else Color(0xFF21262D))
                        .clickable(enabled = enabled) {
                            if (targetKeyframe != null) {
                                val updatedKfs = keyframes.map { kf ->
                                    if (kf.timeUs == targetKeyframe.timeUs) kf.copy(easing = wire) else kf
                                }
                                val updated = when (selectedLayer) {
                                    is MemeEditLayer.Text -> selectedLayer.copy(keyframes = updatedKfs)
                                    is MemeEditLayer.Subject -> selectedLayer.copy(keyframes = updatedKfs)
                                    is MemeEditLayer.Media -> selectedLayer.copy(keyframes = updatedKfs)
                                    else -> selectedLayer
                                }
                                onUpdateLayer(updated)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        label,
                        color = if (selected) Color.Black else if (enabled) Color.White else Color(0xFF8B949E),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Active Range on timeline
        Text("ACTIVE TIME RANGE", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Start", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(44.dp))
            Slider(
                value = (active.startUs / 1_000_000f),
                onValueChange = {
                    val newStart = (it * 1_000_000L).toLong().coerceIn(0L, active.endUs - 100_000L)
                    val updated = when (selectedLayer) {
                        is MemeEditLayer.Text -> selectedLayer.copy(active = TimeRangeUs(newStart, active.endUs))
                        is MemeEditLayer.Subject -> selectedLayer.copy(active = TimeRangeUs(newStart, active.endUs))
                        is MemeEditLayer.Media -> selectedLayer.copy(active = TimeRangeUs(newStart, active.endUs))
                        else -> selectedLayer
                    }
                    onUpdateLayer(updated)
                },
                valueRange = 0f..(durationUs / 1_000_000f),
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
            )
            Text(String.format("%.1fs", active.startUs / 1_000_000.0), color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.width(40.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("End", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(44.dp))
            Slider(
                value = (active.endUs / 1_000_000f),
                onValueChange = {
                    val newEnd = (it * 1_000_000L).toLong().coerceIn(active.startUs + 100_000L, durationUs)
                    val updated = when (selectedLayer) {
                        is MemeEditLayer.Text -> selectedLayer.copy(active = TimeRangeUs(active.startUs, newEnd))
                        is MemeEditLayer.Subject -> selectedLayer.copy(active = TimeRangeUs(active.startUs, newEnd))
                        is MemeEditLayer.Media -> selectedLayer.copy(active = TimeRangeUs(active.startUs, newEnd))
                        else -> selectedLayer
                    }
                    onUpdateLayer(updated)
                },
                valueRange = 0f..(durationUs / 1_000_000f),
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
            )
            Text(String.format("%.1fs", active.endUs / 1_000_000.0), color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.width(40.dp))
        }
    }
}

@Composable
internal fun VideoAudioToolPanel(
    audioSpec: VideoAudioSpec,
    speed: Float,
    musicSpec: VideoMusicSpec?,
    onUpdateAudio: (VideoAudioSpec) -> Unit,
    onUpdateSpeed: (Float) -> Unit,
    onUpdateMusic: (VideoMusicSpec?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Mute & Volume
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (audioSpec.muted) Icons.Default.VolumeMute else Icons.Default.VolumeUp, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Mute Source Audio", color = Color.White, fontSize = 14.sp)
            }
            Switch(
                checked = audioSpec.muted,
                onCheckedChange = { onUpdateAudio(audioSpec.copy(muted = it)) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color(0xFFD8FF4A))
            )
        }

        if (!audioSpec.muted) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volume", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(60.dp))
                Slider(
                    value = audioSpec.volume,
                    onValueChange = { onUpdateAudio(audioSpec.copy(volume = it)) },
                    valueRange = 0f..2f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
                )
                Text("${(audioSpec.volume * 100).roundToInt()}%", color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.width(40.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Playback Speed
        Text("PLAYBACK SPEED", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 4f)
            for (s in speeds) {
                val selected = (speed - s) in -0.01f..0.01f
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color(0xFFD8FF4A) else Color(0xFF21262D))
                        .clickable { onUpdateSpeed(s) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        "${s}x",
                        color = if (selected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Music Controls
        Text("BACKGROUND MUSIC", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        val audioContext = LocalContext.current
        val audioScope = rememberCoroutineScope()
        val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                audioScope.launch {
                    try {
                        val copiedUri = withContext(Dispatchers.IO) {
                            EditorAssetStorage.copyAssetToEditorStorage(audioContext, uri)
                        }
                        onUpdateMusic(VideoMusicSpec(uri = copiedUri.toString(), volume = 1f, startUs = 0L))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Toast.makeText(audioContext, "Could not add the music: ${e.message ?: e}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        if (musicSpec == null) {
            Button(
                onClick = { audioPicker.launch("audio/*") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Select Music Track", color = Color.White)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(musicSpec.uri.takeLast(30), color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onUpdateMusic(null) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove music", tint = Color(0xFFFF5252))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Gain", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(60.dp))
                    Slider(
                        value = musicSpec.volume,
                        onValueChange = { onUpdateMusic(musicSpec.copy(volume = it)) },
                        valueRange = 0f..2f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
                    )
                    Text("${(musicSpec.volume * 100).roundToInt()}%", color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.width(40.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("In-point", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(60.dp))
                    Slider(
                        value = (musicSpec.startUs / 1_000_000f),
                        onValueChange = { onUpdateMusic(musicSpec.copy(startUs = (it * 1_000_000L).toLong())) },
                        valueRange = 0f..30f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFD8FF4A), activeTrackColor = Color(0xFFD8FF4A))
                    )
                    Text(String.format("%.1fs", musicSpec.startUs / 1_000_000.0), color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.width(40.dp))
                }
            }
        }
    }
}
