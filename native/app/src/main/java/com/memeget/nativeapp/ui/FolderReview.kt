package com.memeget.nativeapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Checks a tag folder a meme at a time: swipe right (or ✓) if [label] is right, left (or ✗) if it isn't, Skip to
 * leave it. Undo steps back through any number of answers. Answers are held here and handed to [onClose] in the
 * order given, so the caller saves them in one go: nothing is half-saved while you're still changing your mind.
 * [memes] come in the order to ask them.
 */
@Composable
internal fun FolderReview(
    label: String,
    memes: List<Meme>,
    /** The answers to save, oldest first: true if [label] fits the meme, false if it doesn't. */
    onClose: (answers: List<Pair<Meme, Boolean>>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Answers given, newest last, by meme id: true = right, false = wrong, null = skipped. Saved, so turning the
    // phone mid-check keeps them.
    val history = rememberSaveable(saver = HistorySaver) { mutableStateListOf() }
    // The card on screen: the first one not yet answered (a meme deleted meanwhile is simply gone).
    var index by remember {
        val given = history.mapTo(HashSet()) { it.first }
        mutableIntStateOf(memes.indexOfFirst { it.id !in given }.let { if (it < 0) memes.size else it })
    }
    val answered = history.count { it.second != null }
    val offset = remember { Animatable(0f) }
    var busy by remember { mutableStateOf(false) }
    fun close() {
        val byId = memes.associateBy { it.id }
        onClose(history.mapNotNull { (id, verdict) -> verdict?.let { byId[id]?.let { meme -> meme to verdict } } })
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MemegetColors.Background)) {
            val width = constraints.maxWidth.toFloat()

            fun decide(verdict: Boolean?) {
                val meme = memes.getOrNull(index) ?: return
                if (busy) return
                busy = true
                // Counted the moment it's given, so closing mid-animation still saves it.
                history += meme.id to verdict
                scope.launch {
                    // The card leaves the way it was judged; a skip drops it straight away.
                    val target = when (verdict) { true -> width * 1.3f; false -> -width * 1.3f; null -> 0f }
                    try {
                        if (verdict != null) offset.animateTo(target, tween(180))
                    } finally {
                        index++
                        offset.snapTo(0f)
                        busy = false
                    }
                }
            }
            fun undo() {
                if (busy || history.isEmpty()) return
                val (id, _) = history.removeAt(history.lastIndex)
                memes.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { index = it }
            }

            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    RoundButton("✕", onClick = ::close)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            text = "${minOf(index + 1, memes.size)} of ${memes.size} · $answered answered · is this",
                            color = MemegetColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = label,
                            color = MemegetColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    RoundButton("↶", enabled = history.isNotEmpty(), onClick = ::undo)
                }
                Spacer(Modifier.height(12.dp))

                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val current = memes.getOrNull(index)
                    if (current == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("All checked", color = MemegetColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Done saves your $answered answers. Look-alikes learn from them on the next re-tag.",
                                color = MemegetColors.TextSecondary,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(16.dp))
                            BigButton("Done", MemegetColors.Volt, MemegetColors.Background, onClick = ::close)
                        }
                    } else {
                        // The next card waits underneath, so it's already loaded when this one goes.
                        memes.getOrNull(index + 1)?.let { next ->
                            key(next.id) { ReviewCard(next, Modifier.graphicsLayer { scaleX = 0.94f; scaleY = 0.94f; alpha = 0.6f }) }
                        }
                        key(current.id) {
                            ReviewCard(
                                current,
                                Modifier
                                    .graphicsLayer {
                                        translationX = offset.value
                                        rotationZ = offset.value / width * 12f
                                    }
                                    .pointerInput(current.id) {
                                        detectHorizontalDragGestures(
                                            onDragEnd = {
                                                when {
                                                    offset.value > width * 0.28f -> decide(true)
                                                    offset.value < -width * 0.28f -> decide(false)
                                                    else -> scope.launch { offset.animateTo(0f, spring()) }
                                                }
                                            },
                                            onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                                        ) { change, dx ->
                                            change.consume()
                                            scope.launch { offset.snapTo(offset.value + dx) }
                                        }
                                    },
                            )
                            // Which way it's heading, stronger the further it goes.
                            val lean = (offset.value / (width * 0.28f)).coerceIn(-1f, 1f)
                            if (abs(lean) > 0.05f) {
                                Text(
                                    text = if (lean > 0) "✓ YES" else "✗ NOT “$label”",
                                    color = Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 20.sp,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 24.dp)
                                        .graphicsLayer { alpha = abs(lean) }
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (lean > 0) MemegetColors.Success else MemegetColors.Danger)
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }

                if (index < memes.size) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        BigButton("✗ Not this", MemegetColors.Danger, Color.White, Modifier.weight(1f)) { decide(false) }
                        BigButton("Skip", MemegetColors.SurfaceBright, MemegetColors.TextPrimary, Modifier.weight(0.6f)) { decide(null) }
                        BigButton("✓ Yes", MemegetColors.Success, Color.White, Modifier.weight(1f)) { decide(true) }
                    }
                }
            }
        }
    }
}

/** Answers as a flat list of longs: id, then 0 skipped / 1 right / 2 wrong. */
private val HistorySaver = listSaver<SnapshotStateList<Pair<Long, Boolean?>>, Long>(
    save = { list -> list.flatMap { (id, verdict) -> listOf(id, when (verdict) { null -> 0L; true -> 1L; false -> 2L }) } },
    restore = { flat ->
        flat.chunked(2).map { (id, code) -> id to when (code) { 1L -> true; 2L -> false; else -> null } }.toMutableStateList()
    },
)

@Composable
private fun ReviewCard(meme: Meme, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // A video shows its poster: deciding needs a look, not playback.
    val source = if (meme.thumbUri.isNotEmpty()) meme.thumbUri else meme.uri
    val request = remember(source) { ImageRequest.Builder(context).data(source).crossfade(false).build() }
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(MemegetColors.Surface)
            .border(1.dp, MemegetColors.Border, RoundedCornerShape(24.dp)),
    ) {
        AsyncImage(
            model = request,
            contentDescription = meme.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(8.dp),
        )
        if (meme.kind == "video") {
            Text(
                text = "▶ video",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun BigButton(text: String, fill: Color, ink: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Text(text, color = ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
    }
}

@Composable
private fun RoundButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MemegetColors.Surface)
            .border(1.dp, MemegetColors.Border, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
    ) {
        Text(text, color = MemegetColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
