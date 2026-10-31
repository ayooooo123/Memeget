package com.memeget.nativeapp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.memeget.nativeapp.data.PhraseMining
import com.memeget.nativeapp.data.TagMerge
import kotlin.math.roundToInt

internal enum class BrowseMode(val key: String) {
    Grid("grid"),
    Canvas("canvas"),
    Folders("folders");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: Grid
    }
}

internal enum class FolderKind(val title: String) {
    Tag("Tags"),
    Quote("Quotes"),
    Reaction("Reactions"),
    Format("Formats"),
}

/**
 * A folder on the shelf. A [FolderKind.Tag] folder is a tag ([label] as stored, [key] normalized); the other kinds
 * hold exactly [ids]: memes quoting one line ([query] is the line), a reaction search's best matches ([query] is that
 * search), or one picture template. [count] < 0 means no count is shown. [covers] are the memes on its stack.
 */
internal class MemeFolder(
    val label: String,
    val key: String,
    val count: Int,
    val covers: List<Meme>,
    val taught: Boolean = false,
    val kind: FolderKind = FolderKind.Tag,
    val query: String = "",
    val ids: List<Long> = emptyList(),
)

/** The reply you want to send, and the search that finds memes for it. */
private val REACTIONS = listOf(
    "agree" to "agree based true real facts",
    "cope" to "cope copium seethe",
    "cringe" to "cringe",
    "W" to "W win winning victory",
    "L" to "L loss ratio fail",
    "shocked" to "shocked surprised shock",
    "smug" to "smug",
    "laughing" to "laughing lol funny",
    "crying" to "crying sad tears",
    "angry" to "angry mad rage",
    "confused" to "confused huh what",
    "I'm done" to "done tired over it",
    "hype" to "hype lets go excited",
    "sus" to "sus suspicious",
)

private const val MAX_FOLDERS = 24
/** A reaction folder holds this many of its search's best matches. */
private const val REACTION_SIZE = 60
private const val MIN_FOLDER_SIZE = 2
private const val MORPH_MILLIS = 560
/** The last tile to move starts this far into the morph, so the pile forms in a wave rather than all at once. */
private const val MAX_STAGGER = 0.22f

/**
 * The tags to show as folders, best first, with how many memes carry each. Each pick is the tag that adds the most
 * memes no earlier folder holds: a library's tags are a long tail of overlapping names (pepe, sad pepe, smug pepe),
 * and the most-used tags would mostly repeat the same memes.
 */
internal fun pickFolderKeys(tagSets: List<Set<String>>, maxFolders: Int, minNew: Int): List<Pair<String, Int>> {
    val holders = HashMap<String, MutableList<Int>>()
    tagSets.forEachIndexed { meme, keys -> keys.forEach { holders.getOrPut(it) { ArrayList() } += meme } }
    val covered = BooleanArray(tagSets.size)
    val picked = ArrayList<Pair<String, Int>>(maxFolders)
    while (picked.size < maxFolders && holders.isNotEmpty()) {
        var best: String? = null
        var bestNew = 0
        for ((key, memes) in holders) {
            val added = memes.count { !covered[it] }
            if (added > bestNew || (added == bestNew && best != null && key < best)) {
                best = key
                bestNew = added
            }
        }
        if (best == null || bestNew < minNew) break
        val memes = holders.remove(best)!!
        memes.forEach { covered[it] = true }
        picked += best to memes.size
    }
    return picked
}

/** The library's tags as folders, chosen by [pickFolderKeys]; the labels people taught are starred. */
internal suspend fun loadMemeFolders(store: LibraryStore): List<MemeFolder> = withContext(Dispatchers.IO) {
    val taught = store.taughtLabelNames()
    val labelByKey = LinkedHashMap<String, String>()
    for (label in taught + store.tagLabels()) {
        val key = TagMerge.normalizeTagLabel(label)
        if (key.isNotBlank()) labelByKey.putIfAbsent(key, label)
    }
    val taughtKeys = taught.mapTo(HashSet()) { TagMerge.normalizeTagLabel(it) }
    val tagSets = store.tagKeySets().map { keys -> keys.filterTo(HashSet()) { it in labelByKey } }
    pickFolderKeys(tagSets, MAX_FOLDERS, MIN_FOLDER_SIZE).mapNotNull { (key, count) ->
        val label = labelByKey.getValue(key)
        val covers = store.page(limit = 3, tag = label)
        if (covers.isEmpty()) null else MemeFolder(label, key, count, covers, taught = key in taughtKeys)
    }
}

private suspend fun coversOf(store: LibraryStore, ids: List<Long>): List<Meme> {
    val covers = ArrayList<Meme>(3)
    for (id in ids.take(6)) {
        store.meme(id)?.let { covers += it }
        if (covers.size == 3) break
    }
    return covers
}

/**
 * Lines said in more than one clip (marked 🗣) and lines written on more than one meme, each a folder of the memes
 * that carry it. A quote folder's [MemeFolder.query] is its line.
 */
internal suspend fun loadQuoteFolders(store: LibraryStore): List<MemeFolder> = withContext(Dispatchers.Default) {
    val spoken = PhraseMining.sharedPhrases(store.transcripts(), limit = MAX_FOLDERS / 2).map { it to true }
    val written = PhraseMining.sharedPhrases(store.ocrTexts(), limit = MAX_FOLDERS).map { it to false }
    (spoken + written).distinctBy { it.first.phrase }.take(MAX_FOLDERS).mapNotNull { (line, said) ->
        val covers = coversOf(store, line.memeIds)
        if (covers.isEmpty()) null
        else MemeFolder(
            label = if (said) "🗣 “${line.phrase}”" else "“${line.phrase}”",
            key = "quote:${line.phrase}",
            count = line.memeIds.size,
            covers = covers,
            kind = FolderKind.Quote,
            query = line.phrase,
            ids = line.memeIds,
        )
    }
}

/** Memes sharing one picture with different words, each group a folder. */
internal suspend fun loadFormatFolders(store: LibraryStore): List<MemeFolder> = withContext(Dispatchers.Default) {
    store.memeGroups().formats.take(MAX_FOLDERS).mapIndexedNotNull { i, group ->
        val covers = coversOf(store, group.ids)
        if (covers.isEmpty()) null
        else MemeFolder(group.label.ifBlank { "Format ${i + 1}" }, "format:${group.ids.first()}", group.ids.size, covers, kind = FolderKind.Format, ids = group.ids)
    }
}

/** One folder per reply in [REACTIONS], holding that search's best matches. */
internal suspend fun loadReactionFolders(store: LibraryStore, embed: suspend (String) -> FloatArray?): List<MemeFolder> =
    withContext(Dispatchers.Default) {
        REACTIONS.mapNotNull { (label, query) ->
            val vector = runCatching { embed(query) }.getOrNull()
            val hits = store.search(query, vector, limit = REACTION_SIZE).map { it.meme }
            if (hits.isEmpty()) null
            // Always the search's top matches, so a count would only echo the cap.
            else MemeFolder(label, "reaction:$label", -1, hits.take(3), kind = FolderKind.Reaction, query = query, ids = hits.map { it.id })
        }
    }

/**
 * The folder a meme flies into: [preferred] when the meme is in it, else the first of [folders] holding it (by tag,
 * or by id for quote and format folders). Reaction folders hold search results, so nothing flies into them.
 */
private fun folderKeyFor(meme: Meme, folders: List<MemeFolder>, preferred: String?): String? {
    val tags by lazy { meme.tags.mapTo(HashSet()) { TagMerge.normalizeTagLabel(it.label) } }
    fun holds(folder: MemeFolder) = when (folder.kind) {
        FolderKind.Tag -> folder.key in tags
        FolderKind.Quote, FolderKind.Format, FolderKind.Reaction -> meme.id in folder.ids
    }
    folders.firstOrNull { it.key == preferred }?.let { if (holds(it)) return it.key }
    return folders.firstOrNull(::holds)?.key
}

/** A tile in flight between its canvas place and its folder, drawn at its board size like the canvas draws it. */
private class MorphSprite(val meme: Meme, val width: Int, val height: Int, val canvasRect: Rect, val folderRect: Rect?, val delay: Float, val tilt: Float)

/** Room the floating mode switcher takes at the bottom of each browse view. */
internal val BROWSE_BAR_SPACE = 72.dp

/**
 * Canvas and Folders as one surface. Switching between them is a morph: each tile on screen flies into the stack of
 * the folder it belongs to, and back out again. Opening a folder bursts its memes out of the stack onto the canvas.
 */
@Composable
internal fun MemeBrowser(
    mode: BrowseMode,
    onModeChange: (BrowseMode) -> Unit,
    memes: List<Meme>,
    memesKey: Any?,
    canvasState: MemeCanvasState,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onOpen: (Meme) -> Unit,
    onLongPress: (Meme) -> Unit,
    folders: List<MemeFolder>?,
    openFolderKey: String?,
    onOpenFolder: (MemeFolder) -> Unit,
    onBackToFolders: () -> Unit,
    /** A morph finished and [BrowseMode] is now on screen. */
    onSettled: (BrowseMode) -> Unit,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomInset = BROWSE_BAR_SPACE
    val progress = remember { Animatable(if (mode == BrowseMode.Folders) 1f else 0f) }
    var shownMode by remember { mutableStateOf(mode) }
    var sprites by remember { mutableStateOf<List<MorphSprite>>(emptyList()) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val folderBounds = remember { mutableStateMapOf<String, Rect>() }
    var burstFolder by remember { mutableStateOf<MemeFolder?>(null) }
    // Set for the whole morph, including the frames spent laying out the shelf before any tile moves.
    var morphTarget by remember { mutableStateOf<BrowseMode?>(null) }
    val currentMemesKey by rememberUpdatedState(memesKey)
    val currentMemes by rememberUpdatedState(memes)
    val currentFolders by rememberUpdatedState(folders)
    val currentOpenFolderKey by rememberUpdatedState(openFolderKey)
    val currentOnSettled by rememberUpdatedState(onSettled)

    fun buildSprites(only: String?): List<MorphSprite> {
        val keys = currentFolders.orEmpty()
        val visible = canvasState.visibleTiles()
        if (visible.isEmpty()) return emptyList()
        val top = visible.minOf { canvasState.screenRect(it.first).top }
        val bottom = visible.maxOf { canvasState.screenRect(it.first).bottom }.coerceAtLeast(top + 1f)
        return visible.map { (tile, meme) ->
            val rect = canvasState.screenRect(tile)
            val key = only ?: folderKeyFor(meme, keys, currentOpenFolderKey)
            MorphSprite(
                meme = meme,
                width = tile.width.roundToInt().coerceAtLeast(1),
                height = tile.height.roundToInt().coerceAtLeast(1),
                canvasRect = rect,
                folderRect = key?.let { folderBounds[it] }?.translate(-origin),
                delay = (rect.top - top) / (bottom - top) * MAX_STAGGER,
                tilt = if (meme.id % 2L == 0L) -7f else 6f,
            )
        }
    }

    suspend fun morphTo(target: BrowseMode, only: String? = null) {
        morphTarget = target
        try {
            // The shelf stays composed under the canvas, so every folder's place is already known.
            sprites = buildSprites(only)
            progress.animateTo(if (target == BrowseMode.Folders) 1f else 0f, tween(MORPH_MILLIS, easing = LinearEasing))
            shownMode = target
            currentOnSettled(target)
        } finally {
            sprites = emptyList()
            morphTarget = null
        }
    }

    LaunchedEffect(mode) {
        if (mode == BrowseMode.Grid) return@LaunchedEffect
        if (mode == shownMode) {
            // Switched back before a morph finished: its tiles are gone, so just bring the shelf back to where it was.
            progress.animateTo(if (mode == BrowseMode.Folders) 1f else 0f, tween(MORPH_MILLIS / 3))
            return@LaunchedEffect
        }
        morphTo(mode)
    }
    LaunchedEffect(burstFolder) {
        val folder = burstFolder ?: return@LaunchedEffect
        val before = currentMemesKey
        // Burst once the folder's memes are on the board; a slow load just switches.
        withTimeoutOrNull(1_500) {
            snapshotFlow {
                currentMemesKey != before && canvasState.boardMemes === currentMemes && canvasState.board.tiles.isNotEmpty()
            }.first { it }
        }
        morphTo(BrowseMode.Canvas, only = folder.key)
        burstFolder = null
        onModeChange(BrowseMode.Canvas)
    }

    val morphing = morphTarget != null
    // The canvas stays up until its tiles have been handed to the morph, then the copies take over.
    val showCanvas = shownMode == BrowseMode.Canvas && sprites.isEmpty()

    Box(
        modifier = modifier.onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        // Always laid out, under the canvas: the morph needs every folder's place before the first tile moves.
        FolderShelf(
            folders = folders,
            onOpen = { folder ->
                if (!morphing && burstFolder == null) {
                    burstFolder = folder
                    onOpenFolder(folder)
                }
            },
            onFolderBounds = { key, rect -> folderBounds[key] = rect },
            bottomInset = bottomInset,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Fades in over the second half of the morph, as the tiles land.
                    val shown = ((progress.value - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    alpha = shown
                    val scale = lerp(0.94f, 1f, shown)
                    scaleX = scale
                    scaleY = scale
                },
        )
        if (memes.isNotEmpty()) {
            MemeCanvas(
                memes = memes,
                state = canvasState,
                resetKey = memesKey,
                hasMore = hasMore,
                isLoadingMore = isLoadingMore,
                onLoadMore = onLoadMore,
                onOpen = onOpen,
                onLongPress = onLongPress,
                bottomInset = bottomInset,
                modifier = Modifier
                    .fillMaxSize()
                    .placedOnlyWhen(showCanvas)
                    .background(MemegetColors.Background),
            )
        }
        if (sprites.isNotEmpty()) MorphOverlay(sprites, progress = { progress.value })
        if (showCanvas && openFolderKey != null) {
            val folder = folders?.firstOrNull { it.key == openFolderKey }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = 12.dp, top = 12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MemegetColors.Surface.copy(alpha = 0.9f))
                    .border(1.dp, MemegetColors.Border, RoundedCornerShape(50))
                    .clickable { onBackToFolders() }
                    .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to folders", tint = MemegetColors.TextPrimary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = folder?.let { if (it.count >= 0) "${it.label} · ${it.count}" else it.label } ?: "Folders",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MemegetColors.TextPrimary,
                )
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) { bottomBar() }
    }
}

@Composable
private fun MorphOverlay(sprites: List<MorphSprite>, progress: () -> Float) {
    val context = LocalContext.current
    Layout(
        content = {
            for (sprite in sprites) {
                key(sprite.meme.id) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MemegetColors.SurfaceVariant)) {
                        val meme = sprite.meme
                        MemeTileImage(context, if (meme.thumbUri.isNotEmpty()) meme.thumbUri else meme.uri, meme.name)
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(sprites[i].width, sprites[i].height)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, placeable ->
                val sprite = sprites[i]
                placeable.placeWithLayer(0, 0) {
                    val local = ((progress() - sprite.delay) / (1f - MAX_STAGGER)).coerceIn(0f, 1f)
                    val t = FastOutSlowInEasing.transform(local)
                    val from = sprite.canvasRect
                    val fromScale = from.width / sprite.width
                    val to = sprite.folderRect
                    val center: Offset
                    val scale: Float
                    if (to != null) {
                        // Lands covering the folder's front cover, then gives way to it.
                        val toScale = maxOf(to.width / sprite.width, to.height / sprite.height)
                        center = androidx.compose.ui.geometry.lerp(from.center, to.center, t)
                        scale = lerp(fromScale, toScale, t)
                        rotationZ = sprite.tilt * t
                        alpha = 1f - ((local - 0.72f) / 0.28f).coerceIn(0f, 1f)
                    } else {
                        // Belongs to no folder: sinks away in place.
                        center = from.center
                        scale = fromScale * lerp(1f, 0.6f, t)
                        alpha = 1f - (local / 0.5f).coerceIn(0f, 1f)
                    }
                    transformOrigin = TransformOrigin.Center
                    scaleX = scale
                    scaleY = scale
                    translationX = center.x - sprite.width / 2f
                    translationY = center.y - sprite.height / 2f
                }
            }
        }
    }
}

@Composable
private fun FolderShelf(
    folders: List<MemeFolder>?,
    onOpen: (MemeFolder) -> Unit,
    onFolderBounds: (String, Rect) -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(MemegetColors.Background)) {
        when {
            folders == null -> CircularProgressIndicator(
                color = MemegetColors.Volt,
                strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.Center).size(24.dp),
            )
            folders.isEmpty() -> Text(
                text = "No folders yet. Tags from indexing and the labels you teach become folders.",
                style = MaterialTheme.typography.bodyMedium,
                color = MemegetColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomInset + 16.dp)),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // One section per kind, in the order of FolderKind: tags, quotes, reactions, formats.
                folders.groupBy { it.kind }.toSortedMap().forEach { (kind, inKind) ->
                    Text(
                        text = "${kind.title} · ${inKind.size}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MemegetColors.TextSecondary,
                        modifier = Modifier.padding(start = 6.dp, top = 6.dp),
                    )
                    inKind.chunked(3).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            row.forEach { folder ->
                                FolderCell(folder, onOpen, onFolderBounds, Modifier.weight(1f))
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderCell(folder: MemeFolder, onOpen: (MemeFolder) -> Unit, onFolderBounds: (String, Rect) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onOpen(folder) }
            .padding(vertical = 6.dp),
    ) {
        Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            // Back to front: the newest meme sits on top, older ones fan out behind it.
            folder.covers.withIndex().reversed().forEach { (i, cover) ->
                val coverModifier = Modifier
                    .size(76.dp)
                    .graphicsLayer {
                        rotationZ = when (i) { 1 -> 7f; 2 -> -8f; else -> 0f }
                        translationX = when (i) { 1 -> 6.dp.toPx(); 2 -> -6.dp.toPx(); else -> 0f }
                        val scale = when (i) { 1 -> 0.92f; 2 -> 0.86f; else -> 1f }
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, MemegetColors.Background, RoundedCornerShape(16.dp))
                    .background(MemegetColors.SurfaceVariant)
                Box(
                    // Unclipped: a folder scrolled out of view still has a place for its tiles to fly to.
                    modifier = if (i == 0) coverModifier.onGloballyPositioned { onFolderBounds(folder.key, Rect(it.positionInRoot(), it.size.toSize())) } else coverModifier,
                ) {
                    MemeTileImage(context, if (cover.thumbUri.isNotEmpty()) cover.thumbUri else cover.uri, cover.name)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (folder.taught) "★ ${folder.label}" else folder.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MemegetColors.TextPrimary,
            // A quote reads best whole: two lines before it's cut.
            maxLines = if (folder.kind == FolderKind.Quote) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (folder.count >= 0) {
            Text(
                text = folder.count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MemegetColors.TextMuted,
            )
        }
    }
}

/** Floating Grid / Canvas / Folders switch. */
@Composable
internal fun BrowseModeSwitcher(mode: BrowseMode, onSelect: (BrowseMode) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MemegetColors.Surface.copy(alpha = 0.92f))
            .border(1.dp, MemegetColors.Border, RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BrowseMode.entries.forEach { entry ->
            val selected = entry == mode
            val background by animateColorAsState(if (selected) MemegetColors.SurfaceBright else MemegetColors.Surface.copy(alpha = 0f), label = "segment")
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 52.dp, height = 38.dp)
                    .clip(RoundedCornerShape(50))
                    .background(background)
                    .clickable { onSelect(entry) },
            ) {
                Icon(
                    imageVector = when (entry) {
                        BrowseMode.Grid -> Icons.Filled.GridView
                        BrowseMode.Canvas -> Icons.Filled.Dashboard
                        BrowseMode.Folders -> Icons.Filled.Folder
                    },
                    contentDescription = entry.name,
                    tint = if (selected) MemegetColors.Volt else MemegetColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
