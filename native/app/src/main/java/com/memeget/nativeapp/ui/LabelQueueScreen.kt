package com.memeget.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.memeget.nativeapp.data.TagMerge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Asks about the memes whose answers spread furthest. Every answer is a teaching: "Yes" confirms
 * a suggested label, "Not this" rejects it, a typed label names the meme. Answers are also the
 * held-out evidence that lets a label apply to look-alikes on its own.
 */
@Composable
internal fun LabelQueueScreen(
    store: LibraryStore,
    knowledge: LocalKnowledge,
    cloud: CloudAssistant,
    onOpenViewer: (Meme) -> Unit,
    onAnswered: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var queue by remember { mutableStateOf<List<LocalKnowledge.QueueItem>?>(null) }
    var knownLabels by remember { mutableStateOf<List<String>>(emptyList()) }
    var answered by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        queue = null
        error = null
        try {
            knownLabels = store.tagLabels()
            queue = knowledge.labelQueue()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not build the labeling queue"
            queue = emptyList()
        }
    }

    val item = queue?.firstOrNull()
    // The current row, refreshed after a "not this" so its rejection drops out of the suggestions.
    var meme by remember(item) { mutableStateOf(item?.meme) }
    var suggestions by remember(item) { mutableStateOf<List<Tag>>(emptyList()) }
    var typed by remember(item) { mutableStateOf("") }
    LaunchedEffect(item, meme) {
        val current = meme
        suggestions = if (item != null && current != null) knowledge.suggestFor(current, item.image) else emptyList()
    }

    fun answer(label: String, category: String?, positive: Boolean) {
        val current = item ?: return
        if (busy || label.isBlank()) return
        busy = true
        scope.launch {
            try {
                cloud.recordTeaching(current.meme.id, label, category ?: knowledge.knownCategory(label), positive, emptyList())
                answered++
                onAnswered()
                if (positive) queue = queue?.drop(1) else meme = store.meme(current.meme.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not save that answer"
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Label memes", style = MaterialTheme.typography.titleMedium, color = MemegetColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text("$answered answered", style = MaterialTheme.typography.labelSmall, color = MemegetColors.TextSecondary)
            TextButton(onClick = { refresh++ }, enabled = queue != null) { Text("Refresh") }
        }
        Text(
            "Look-alikes get a label on their own once your answers show it holds.",
            style = MaterialTheme.typography.bodySmall,
            color = MemegetColors.TextMuted,
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MemegetColors.Danger) }

        when {
            queue == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = MemegetColors.Volt, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Finding the memes worth labeling…", color = MemegetColors.TextSecondary)
                }
            }
            item == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing to label right now. New memes and new labels bring more.",
                    color = MemegetColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            else -> {
                QueuePicture(meme ?: item.meme, Modifier.fillMaxWidth().weight(1f)) { onOpenViewer(meme ?: item.meme) }
                Text(
                    if (item.lookalikes > 0) "Stands for ${item.lookalikes} similar unlabeled memes" else "No close look-alikes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MemegetColors.TextSecondary,
                )
                for (tag in suggestions) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tag.label, style = MaterialTheme.typography.bodyLarge, color = MemegetColors.TextPrimary, modifier = Modifier.weight(1f))
                        Button(
                            onClick = { answer(tag.label, tag.category, true) },
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = MemegetColors.Volt, contentColor = MemegetColors.Background),
                        ) { Text("Yes") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { answer(tag.label, tag.category, false) }, enabled = !busy) { Text("Not this") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.lowercase() },
                        label = { Text("Name it") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { answer(TagMerge.normalizeTagLabel(typed), null, true) }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { answer(TagMerge.normalizeTagLabel(typed), null, true) }, enabled = typed.isNotBlank() && !busy) { Text("Add") }
                }
                val prefix = TagMerge.normalizeTagLabel(typed)
                val matches = remember(prefix, knownLabels) {
                    if (prefix.isEmpty()) emptyList() else knownLabels.filter { it.startsWith(prefix) && it != prefix }.take(6)
                }
                if (matches.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (match in matches) AssistChip(onClick = { typed = match }, label = { Text(match) })
                    }
                }
                TextButton(onClick = { queue = queue?.drop(1) }, enabled = !busy) { Text("Skip") }
            }
        }
    }
}

@Composable
private fun QueuePicture(meme: Meme, modifier: Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MemegetColors.SurfaceVariant)
            .clickable(onClick = onOpen),
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context).data(meme.thumbUri.ifEmpty { meme.uri }).crossfade(150).build(),
            contentDescription = meme.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (meme.kind == "video") "🎞 ${meme.name}" else "🖼 ${meme.name}", color = MemegetColors.TextMuted, textAlign = TextAlign.Center)
                }
            },
        )
        if (meme.kind == "video") {
            Text(
                "▶ Tap to play",
                style = MaterialTheme.typography.labelSmall,
                color = MemegetColors.TextPrimary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(MemegetColors.Background.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
