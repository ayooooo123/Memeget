package com.memeget.nativeapp

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.memeget.nativeapp.data.TagMerge
import com.memeget.nativeapp.knowledge.Titles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Asks about the memes whose answers spread furthest. Every answer is a teaching: "Yes" confirms
 * a suggested label, "Not this" rejects it, a typed label names the meme. Answers are also the
 * held-out evidence that lets a label apply to look-alikes on its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LabelQueueScreen(
    store: LibraryStore,
    knowledge: LocalKnowledge,
    onOpenViewer: (Meme) -> Unit,
    /** Saves an answer as a teaching. It must outlive this screen: leaving the tab mid-save keeps the answer. */
    saveAnswer: suspend (memeId: Long, label: String, category: String?, positive: Boolean) -> Unit,
    /** Removes a confirmed tag and its positive teaching from this meme. */
    removeAnswer: suspend (memeId: Long, label: String) -> Unit = { memeId, label -> store.removeTag(memeId, label) },
) {
    val context = LocalContext.current
    val uiPrefs = remember { context.getSharedPreferences("memeget_ui", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val revision by store.revision.collectAsState()
    val focusManager = LocalFocusManager.current
    var refresh by remember { mutableIntStateOf(0) }
    var queue by remember { mutableStateOf<List<LocalKnowledge.QueueItem>?>(null) }
    var history by remember { mutableStateOf<List<LocalKnowledge.QueueItem>>(emptyList()) }
    var knownLabels by remember { mutableStateOf<List<String>>(emptyList()) }
    var answered by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        queue = null
        history = emptyList()
        error = null
        try {
            knownLabels = store.tagLabels()
            val built = knowledge.labelQueue()
            val activeId = if (refresh == 0) uiPrefs.getLong("activeLabelMemeId", -1L) else -1L
            if (refresh > 0) uiPrefs.edit().remove("activeLabelMemeId").apply()
            queue = if (activeId > 0L && built.firstOrNull()?.meme?.id != activeId) {
                val existing = built.firstOrNull { it.meme.id == activeId && it.ask == null }
                if (existing != null) {
                    listOf(existing) + built.filterNot { it === existing }
                } else {
                    val savedMeme = store.meme(activeId)
                    val savedVec = store.vectors(activeId)?.image
                    if (savedMeme != null && savedVec != null && savedVec.size == 512) {
                        val lookalikes = uiPrefs.getInt("activeLabelLookalikes", 0)
                        listOf(LocalKnowledge.QueueItem(savedMeme, savedVec, lookalikes)) + built
                    } else {
                        built
                    }
                }
            } else {
                built
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not build the labeling queue"
            queue = emptyList()
        }
    }

    val item = queue?.firstOrNull()
    // The current row, refreshed after each answer or viewer edit so tags and suggestions stay in sync.
    var meme by remember(item) { mutableStateOf(item?.meme) }
    var addedOnCard by remember(item) {
        mutableIntStateOf(if (item?.ask == null) item?.meme?.tags?.count(TagMerge::isHumanTag) ?: 0 else 0)
    }
    var suggestions by remember(item) { mutableStateOf<List<Tag>>(emptyList()) }
    var typed by remember(item) { mutableStateOf("") }
    LaunchedEffect(item?.meme?.id, revision) {
        val id = item?.meme?.id ?: return@LaunchedEffect
        store.meme(id)?.let { meme = it }
    }
    LaunchedEffect(item, meme) {
        val current = meme
        val ask = item?.ask
        suggestions = when {
            item == null || current == null -> emptyList()
            ask != null -> listOf(Tag(ask.label, ask.category))
            else -> knowledge.suggestFor(current, item.image)
        }
    }

    fun nextCard(askVote: Boolean? = null) {
        focusManager.clearFocus()
        val current = item ?: return
        uiPrefs.edit().remove("activeLabelMemeId").remove("activeLabelLookalikes").apply()
        history = (history + current).takeLast(20)
        queue = queue?.let { LocalKnowledge.advance(it, askVote) }
    }

    fun previousCard() {
        val prev = history.lastOrNull() ?: return
        focusManager.clearFocus()
        history = history.dropLast(1)
        if (prev.ask == null) {
            uiPrefs.edit().putLong("activeLabelMemeId", prev.meme.id).putInt("activeLabelLookalikes", prev.lookalikes).apply()
        }
        queue = listOf(prev) + (queue ?: emptyList()).filterNot { it.meme.id == prev.meme.id && it.ask?.label == prev.ask?.label }
    }

    fun answerMany(entries: List<Pair<String, String?>>, positive: Boolean, fromAskRow: Boolean = false, clearTyped: Boolean = false) {
        val current = item ?: return
        val cleaned = entries
            .map { (raw, cat) -> TagMerge.normalizeTagLabel(raw) to cat }
            .filter { (norm, _) -> norm.isNotEmpty() }
            .distinctBy { it.first }
        if (busy || cleaned.isEmpty()) return
        focusManager.clearFocus()
        busy = true
        scope.launch {
            try {
                withContext(NonCancellable) {
                    for ((norm, cat) in cleaned) {
                        saveAnswer(current.meme.id, norm, cat, positive)
                        answered++
                        if (positive) addedOnCard++
                    }
                }
                if (clearTyped) typed = ""
                if (positive) {
                    val added = cleaned.map { it.first }.filter { it !in knownLabels }
                    if (added.isNotEmpty()) knownLabels = (knownLabels + added).distinct().sorted()
                }
                val ask = current.ask
                if (ask != null && fromAskRow && cleaned.size == 1 && cleaned.single().first == ask.label) {
                    uiPrefs.edit().remove("activeLabelMemeId").remove("activeLabelLookalikes").apply()
                    history = (history + current).takeLast(20)
                    queue = queue?.let { LocalKnowledge.advance(it, positive) }
                } else {
                    uiPrefs.edit().putLong("activeLabelMemeId", current.meme.id).putInt("activeLabelLookalikes", current.lookalikes).apply()
                    meme = store.meme(current.meme.id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not save that answer"
            } finally {
                busy = false
            }
        }
    }

    fun unanswer(label: String) {
        val current = item ?: return
        if (busy) return
        focusManager.clearFocus()
        busy = true
        scope.launch {
            try {
                removeAnswer(current.meme.id, label)
                if (addedOnCard > 0) addedOnCard--
                if (answered > 0) answered--
                meme = store.meme(current.meme.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not remove that tag"
            } finally {
                busy = false
            }
        }
    }

    fun parseTypedEntries(input: String): List<Pair<String, String?>> =
        input.split(',').map { it to null }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Label memes", style = MaterialTheme.typography.titleMedium, color = MemegetColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text("$answered answered", style = MaterialTheme.typography.labelSmall, color = MemegetColors.TextSecondary)
            if (history.isNotEmpty()) {
                TextButton(onClick = { previousCard() }, enabled = !busy && queue != null) { Text("Previous") }
            }
            TextButton(onClick = { refresh++ }, enabled = !busy && queue != null) { Text("Refresh") }
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
            // The app draws edge to edge, so the keyboard does not resize the window: imePadding shrinks
            // this scroll area instead, which keeps the focused name field (matches above it) in view.
            else -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f).imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QueuePicture(meme ?: item.meme, Modifier.fillMaxWidth().height(220.dp)) { onOpenViewer(meme ?: item.meme) }
                val ask = item.ask
                fun memes(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"
                Text(
                    when {
                        ask == null -> if (item.lookalikes > 0) "Stands for ${memes(item.lookalikes, "similar unlabeled meme")}" else "No close look-alikes"
                        ask.needed == 1 -> "You tagged this “${ask.taggedAs.joinToString(", ")}”. If it is also ${ask.label}, " +
                            "a Yes here lets ${ask.label} label ${memes(ask.unlocks, "look-alike")} on its own."
                        else -> "You tagged this “${ask.taggedAs.joinToString(", ")}”. If it is also ${ask.label}, " +
                            "${ask.needed} Yes answers like this let ${ask.label} label ${memes(ask.unlocks, "look-alike")} on its own."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MemegetColors.TextSecondary,
                )
                val currentMeme = meme ?: item.meme
                val humanTags = remember(currentMeme) { currentMeme.tags.filter(TagMerge::isHumanTag) }
                val rejectedLabels = remember(currentMeme) { currentMeme.rejectedLabels }
                if (humanTags.isNotEmpty() || rejectedLabels.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (tag in humanTags) {
                            AssistChip(
                                onClick = { unanswer(tag.label) },
                                enabled = !busy,
                                label = { Text("✓ ${tag.label}  ✕", color = MemegetColors.Volt) },
                            )
                        }
                        for (label in rejectedLabels) {
                            AssistChip(
                                onClick = { unanswer(label) },
                                enabled = !busy,
                                label = { Text("✗ $label  ✕", color = MemegetColors.TextSecondary) },
                            )
                        }
                    }
                }
                for (tag in suggestions) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // A TV show or movie asks whether the meme is from it.
                        val question = if (Titles.isTitleCategory(tag.category)) "From ${tag.label}? (${categoryName(tag.category)})" else tag.label
                        Text(question, style = MaterialTheme.typography.bodyLarge, color = MemegetColors.TextPrimary, modifier = Modifier.weight(1f))
                        Button(
                            onClick = { answerMany(listOf(tag.label to tag.category), positive = true, fromAskRow = ask != null) },
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = MemegetColors.Volt, contentColor = MemegetColors.Background),
                        ) { Text("Yes") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { answerMany(listOf(tag.label to tag.category), positive = false, fromAskRow = ask != null) },
                            enabled = !busy,
                        ) { Text("Not this") }
                    }
                }
                val lastSegment = typed.substringAfterLast(',')
                val prefix = TagMerge.normalizeTagLabel(lastSegment)
                val matches = remember(prefix, knownLabels, humanTags) {
                    val settled = humanTags.mapTo(HashSet()) { TagMerge.normalizeTagLabel(it.label) }
                    if (prefix.isEmpty()) emptyList()
                    else knownLabels.filter { it.startsWith(prefix) && it != prefix && it !in settled }.take(6)
                }
                // The field's own scroll-into-view runs before the keyboard finishes opening; repeat it after.
                val inputArea = remember { BringIntoViewRequester() }
                var inputFocused by remember { mutableStateOf(false) }
                val keyboardOpen = WindowInsets.isImeVisible
                LaunchedEffect(inputFocused, keyboardOpen, matches.isNotEmpty()) {
                    if (inputFocused && keyboardOpen) {
                        delay(300)
                        inputArea.bringIntoView()
                    }
                }
                Column(Modifier.bringIntoViewRequester(inputArea), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (matches.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (match in matches) {
                                AssistChip(
                                    onClick = {
                                        val before = typed.substringBeforeLast(',', "")
                                        typed = if (before.isBlank()) match else "${before.trimEnd()}, $match"
                                    },
                                    label = { Text(match) },
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it.lowercase() },
                            label = { Text("Add tag(s) — comma-separate for multiple") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (typed.isNotBlank()) {
                                        answerMany(parseTypedEntries(typed), positive = true, clearTyped = true)
                                    } else if (addedOnCard > 0) {
                                        nextCard()
                                    }
                                },
                            ),
                            modifier = Modifier.weight(1f).onFocusChanged { inputFocused = it.isFocused },
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { answerMany(parseTypedEntries(typed), positive = true, clearTyped = true) },
                            enabled = typed.isNotBlank() && !busy,
                        ) { Text("Add") }
                    }
                }
                if (addedOnCard > 0) {
                    Button(
                        onClick = { nextCard() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MemegetColors.Volt, contentColor = MemegetColors.Background),
                    ) { Text("Next meme") }
                } else {
                    TextButton(onClick = { nextCard() }, enabled = !busy) { Text("Skip") }
                }
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
