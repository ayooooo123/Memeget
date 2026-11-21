package com.memeget.nativeapp.editor

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

private val FADES_US = listOf(0L, 500_000L, 1_000_000L, 2_000_000L)
private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 4f)
private val WAVE_HEIGHT = 56.dp

/**
 * Sound: the added music first (pick any audio or video file, drag its waveform to choose the in-point, set where
 * it comes in, fades, loop), then the clip's own sound and the speed.
 */
@Composable
internal fun VideoAudioPanel(
    project: MemeEditProject,
    playheadUs: Long,
    onUpdateAudio: (VideoAudioSpec) -> Unit,
    onLiveAudio: (VideoAudioSpec) -> Unit,
    onUpdateSpeed: (Float) -> Unit,
    onUpdateMusic: (VideoMusicSpec?) -> Unit,
    onLiveMusic: (control: String, VideoMusicSpec) -> Unit,
    onHearMusic: () -> Unit,
    modifier: Modifier = Modifier
) {
    val video = project.video ?: VideoEditSpec()
    val music = video.music
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        picking = true
        scope.launch {
            try {
                onUpdateMusic(importSound(context, picked, music))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Could not add the sound", Toast.LENGTH_LONG).show()
            } finally {
                picking = false
            }
        }
    }
    val segments = remember(project.video, project.source) { TimelineEdits.segments(project) }
    val videoOutputUs = TimelineEdits.outputDurationUs(segments)
    val playheadOutputUs = ProjectCore.mapSourceTimeToOutputTime(playheadUs, segments, video.speed)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (music == null) {
            SectionLabel("MUSIC")
            StudioChip(
                if (picking) "Adding…" else "♪ Add a song or a video's sound",
                if (picking) null else ({ picker.launch(arrayOf("audio/*", "video/*")) }),
                color = StudioColors.Music
            )
            Text("Any video works: its sound plays over this clip.", color = StudioColors.Muted, fontSize = 11.sp)
        } else {
            val plan = remember(music, videoOutputUs) { MusicPlan.of(music, videoOutputUs) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "♪ ${music.name ?: "Music"}" + (music.durationUs?.let { " · ${formatTimecode(it)}" } ?: ""),
                    color = StudioColors.Music,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                StudioChip("▶ Hear", onHearMusic)
                StudioChip("Replace", if (picking) null else ({ picker.launch(arrayOf("audio/*", "video/*")) }))
                StudioChip("✕", { onUpdateMusic(null) }, color = StudioColors.Danger)
            }
            MusicWaveformStrip(music, plan, onInPoint = { onUpdateMusic(music.copy(startUs = it)) })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "In at ${formatTimecode(music.startUs)} · comes in at ${formatTimecode(music.atUs)}",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                StudioChip("At playhead", playheadOutputUs?.let { at -> { onUpdateMusic(music.copy(atUs = at)) } })
                StudioChip("At start", if (music.atUs != 0L) ({ onUpdateMusic(music.copy(atUs = 0L)) }) else null)
            }
            if (plan == null) {
                val reason = if ((music.durationUs ?: Long.MAX_VALUE) < MusicPlan.MIN_PIECE_US)
                    "This sound is shorter than 0.05 s. Replace it with a longer sound."
                else "Less than 0.05 s of video remains at this start time."
                Text(reason, color = StudioColors.Danger, fontSize = 11.sp)
            } else if (plan.loopCut) {
                Text(
                    "Too short to loop to the end (at most ${MusicPlan.MAX_PIECES} loops): it stops at ${formatTimecode(plan.endUs)}.",
                    color = StudioColors.Danger,
                    fontSize = 11.sp
                )
            }
            LevelRow("Music", music.volume) { onLiveMusic("gain", music.copy(volume = it)) }
            ChipRow("Fade in") {
                for (us in FADES_US) StudioChip(fadeLabel(us), { onUpdateMusic(music.copy(fadeInUs = us)) }, selected = music.fadeInUs == us)
            }
            ChipRow("Fade out") {
                for (us in FADES_US) StudioChip(fadeLabel(us), { onUpdateMusic(music.copy(fadeOutUs = us)) }, selected = music.fadeOutUs == us)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Loop to the end", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = music.loop,
                    onCheckedChange = { onUpdateMusic(music.copy(loop = it)) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = StudioColors.Accent)
                )
            }
        }

        SectionLabel("CLIP SOUND")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mute", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = video.audio.muted,
                onCheckedChange = { onUpdateAudio(video.audio.copy(muted = it)) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = StudioColors.Accent)
            )
        }
        if (!video.audio.muted) {
            LevelRow("Clip", video.audio.volume) { onLiveAudio(video.audio.copy(volume = it)) }
        }

        SectionLabel("SPEED")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in SPEEDS) StudioChip("${s}x", { onUpdateSpeed(s) }, selected = (video.speed - s) in -0.01f..0.01f)
        }
        if (music != null && video.speed != 1f) {
            Text("The music keeps its own tempo.", color = StudioColors.Muted, fontSize = 11.sp)
        }
    }
}

/** An in-point being dragged on the sound at [uri]; once the sound is replaced, it is stale. */
private data class InPointDraft(val uri: String, val us: Long)

/**
 * The track's waveform under a fixed line: drag it to choose the in-point, pinch to zoom. The bright part is
 * what plays (to the video's end, or the track's); the rest is dimmed.
 */
@Composable
private fun MusicWaveformStrip(music: VideoMusicSpec, plan: MusicPlan?, onInPoint: (Long) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val waveform by produceState<Waveform?>(null, music.uri) {
        value = try {
            Waveform.load(context, music.uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Waveform(FloatArray(0))
        }
    }
    val trackUs = music.durationUs ?: waveform?.durationUs ?: 0L
    if (trackUs <= 0L) {
        Box(Modifier.fillMaxWidth().height(WAVE_HEIGHT)) {
            Text("Reading the sound…", color = StudioColors.Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
        }
        return
    }
    val heardUs = plan?.pieces?.firstOrNull()?.durationUs ?: 0L
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth.toFloat()
        // Start zoomed so the heard part fills about half the strip: that's the part worth placing precisely.
        val startPxPerUs = widthPx * 0.5 / max(heardUs, 1_000_000L)
        val minPxPerUs = widthPx * 0.5 / trackUs
        // A sound shorter than about 0.1 s already fills the strip zoomed out; its range then is that one zoom.
        val maxPxPerUs = maxOf(with(density) { 40.dp.toPx() } / 20_000.0, minPxPerUs)
        var pxPerUs by remember(music.uri, widthPx) { mutableDoubleStateOf(startPxPerUs.coerceIn(minPxPerUs, maxPxPerUs)) }
        var draft by remember { mutableStateOf<InPointDraft?>(null) }
        val inPoint = draft?.takeIf { it.uri == music.uri }?.us ?: music.startUs
        val scale = StripScale(inPoint, pxPerUs, widthPx)
        val maxInPoint = (trackUs - MusicPlan.MIN_PIECE_US).coerceAtLeast(0L)
        // Gestures outlive recompositions; they read the newest values through these.
        val latestUri by rememberUpdatedState(music.uri)
        val latestScale by rememberUpdatedState(scale)
        val latestCommit by rememberUpdatedState(onInPoint)
        val latestMaxInPoint by rememberUpdatedState(maxInPoint)
        val zoomBy by rememberUpdatedState { factor: Float -> pxPerUs = (pxPerUs * factor).coerceIn(minPxPerUs, maxPxPerUs) }
        val rulerPx = with(density) { 14.dp.toPx() }
        val heightDp = WAVE_HEIGHT + 14.dp

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(heightDp)
                .scrubGestures(
                    pxPerUs = { latestScale.pxPerUs },
                    grabAt = { null },
                    onScrubStart = { draft = InPointDraft(latestUri, latestScale.centerUs) },
                    onScrubBy = { delta ->
                        draft = draft?.takeIf { it.uri == latestUri }?.let { it.copy(us = (it.us + delta).coerceIn(0L, latestMaxInPoint)) }
                    },
                    onZoom = { zoomBy(it) },
                    onHandleMove = { _, _ -> },
                    onTap = { p -> latestCommit(latestScale.us(p.x).coerceIn(0L, latestMaxInPoint)) },
                    onEnd = {
                        draft?.takeIf { it.uri == latestUri }?.let { latestCommit(it.us) }
                        draft = null
                    }
                )
        ) {
            drawRuler(scale, 0L, trackUs, rulerPx, measurer)
            val top = rulerPx
            val height = size.height - top
            val mid = top + height / 2
            val trackStart = scale.x(0L).coerceAtLeast(0f)
            val trackEnd = scale.x(trackUs).coerceAtMost(size.width)
            drawRect(Color(0xFF0D1117), Offset(trackStart, top), Size(trackEnd - trackStart, height))
            val heardFrom = scale.x(inPoint)
            val heardTo = scale.x(inPoint + heardUs)
            drawRoundRect(StudioColors.Music.copy(alpha = 0.18f), Offset(heardFrom, top), Size(heardTo - heardFrom, height), CornerRadius(4.dp.toPx()))
            val bar = 2.dp.toPx()
            val wave = waveform
            if (wave != null && wave.peaks.isNotEmpty()) {
                var x = trackStart
                while (x < trackEnd) {
                    val peak = wave.peakBetween(scale.us(x), scale.us(x + bar))
                    val h = max(1.dp.toPx(), peak * height * 0.9f)
                    val heard = x >= heardFrom && x < heardTo
                    drawRect(
                        if (heard) StudioColors.Music else StudioColors.Muted.copy(alpha = 0.5f),
                        Offset(x, mid - h / 2),
                        Size(bar * 0.7f, h)
                    )
                    x += bar
                }
            }
            val line = 2.dp.toPx()
            drawRect(Color.White, Offset(size.width / 2 - line / 2, top), Size(line, height))
        }
    }
}

/** Copies a picked file into editor storage and makes it the music, keeping [previous]'s settings. */
private suspend fun importSound(context: android.content.Context, picked: Uri, previous: VideoMusicSpec?): VideoMusicSpec =
    withContext(Dispatchers.IO) {
        val copied = EditorAssetStorage.copyAssetToEditorStorage(context, picked)
        val trackUs = try {
            probeAudioTrackUs(context, copied.toString())
        } catch (e: Exception) {
            File(copied.path!!).delete()
            throw e
        }
        val unusable = when {
            trackUs == null -> "That file has no sound"
            // A shorter sound could never be planned (see MusicPlan.MIN_PIECE_US), so it would never be heard.
            trackUs < MusicPlan.MIN_PIECE_US -> "That sound is too short: it must last at least 0.05 s"
            else -> null
        }
        if (unusable != null) {
            File(copied.path!!).delete()
            throw java.io.IOException(unusable)
        }
        val name = EditorAssetStorage.displayName(context, picked)
        (previous ?: VideoMusicSpec(uri = "")).copy(
            uri = copied.toString(),
            name = name,
            durationUs = trackUs,
            startUs = 0L
        )
    }

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = StudioColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun LevelRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(48.dp))
        Slider(
            value = value,
            onValueChange = { onChange((it * 20).roundToInt() / 20f) },
            valueRange = 0f..2f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = StudioColors.Accent, activeTrackColor = StudioColors.Accent)
        )
        Text("${(value * 100).roundToInt()}%", color = StudioColors.Muted, fontSize = 12.sp, modifier = Modifier.width(44.dp))
    }
}

@Composable
private fun ChipRow(label: String, chips: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
}

private fun fadeLabel(us: Long): String = if (us == 0L) "Off" else "%.1fs".format(us / 1_000_000.0).replace(".0s", "s")
