package com.memeget.nativeapp.knowledge

import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.TextRegion
import com.memeget.nativeapp.data.FloatBlob
import com.memeget.nativeapp.data.TagMerge
import java.util.Locale
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

internal enum class MemeGateVerdict(val code: Int) {
    REJECTED(0),
    ACCEPTED(1),
    UNCERTAIN_TEXT(2),
    UNCERTAIN_VISUAL(3);

    companion object {
        fun fromCode(code: Int): MemeGateVerdict = entries.firstOrNull { it.code == code } ?: REJECTED
    }
}

internal data class MemeGateStage1Decision(
    val verdict: MemeGateVerdict,
    val memeProbability: Float,
    val logit: Float,
    val lineCount: Int,
    val charCount: Int,
    val verticalSpacingCv: Float,
    val hasCaptionBand: Boolean,
    val receiptDocScore: Float,
    val mundaneUiScore: Float,
    val memeLexicalScore: Float,
    val d1Prompt: String,
    val reason: String,
)

internal data class TwoDomainIdentification(
    val scene: String,
    val baseReference: String,
    val targetTopic: String,
    val replyIntent: String,
    val tags: List<Tag>,
    val combinedCaption: String,
    val searchTerms: String,
)

/**
 * Pure-Kotlin + C++-aligned 3-Stage Camera-Roll Meme Gate & Two-Domain Composite Parser:
 * Stage 1 (<0.2 ms): MobileCLIP-S2 + OpenVocabConcepts + ordinary-photo anchors + OCR box topology + panel gutters.
 * Stage 2 (~50 ms): Fine-tuned LiquidAI/d1-omni-600M System-1 prompt evaluation on borderline text screenshots.
 * Stage 3: MiniCPM-V 4.7 1B 1-token YES/NO gate with zero-re-encode continuation into two-domain identification.
 */
internal object MemeGateEvaluator {

    private val RECEIPT_CUES = listOf(
        " subtotal ", " sales tax ", " total ", " invoice ", " order number ", " visa ",
        " mastercard ", " balance due ", " amount paid ", " tracking number ", " receipt ",
        " qty ", " unit price ", " billing ", " routing number ", " account summary ",
    )

    private val MUNDANE_UI_CUES = listOf(
        " settings ", " bluetooth ", " cellular ", " battery usage ", " verification code ",
        " two factor ", " flight ", " boarding pass ", " calendar ", " alarm ", " screen time ",
        " storage ", " wi fi ", " password ", " sign in ", " starting service ", " intent ",
        " customize toolbar ", " clean energy ", " upgraded ", " newly installed ", " node pubkey ",
        " micromodal ", " lock screen ", " supplement ", " chewables ", " mylar packaging ",
        " appetizers ", " sushi ", " faq ", " hiring ", " alldebrid ",
    )

    private val MEME_LEXICAL_CUES = listOf(
        " pov:", " pov ", " nobody:", " me:", " me when ", " when you ", " mfw ", " tfw ",
        " bro ", " literally me ", " be like ", " >be me ", " wojak ", " pepe ", " cooked ",
        " joever ", " copium ", " real ", " shitpost ", " mf ", " bruh ", " lmao ",
    )

    fun evaluateStage1(
        width: Int,
        height: Int,
        isVideo: Boolean,
        durationMs: Long,
        isScreenshotHint: Boolean,
        imageVec: FloatArray,
        openVocab: OpenVocabConcepts?,
        curatedAnchors: List<NegativeAnchorDef>,
        curatedLabels: List<CuratedLabelDef>,
        rejectedUserVectors: List<FloatArray>,
        detectedPanelCount: Int,
        hasPanelGutter: Boolean,
        ocrRegions: List<TextRegion>,
        fullOcrText: String,
    ): MemeGateStage1Decision {
        // 0. Online user "Not a meme" rejection memory veto
        var maxRejectedCos = -1f
        for (rv in rejectedUserVectors) {
            if (rv.size == imageVec.size) {
                val c = FloatBlob.dot(imageVec, rv)
                if (c > maxRejectedCos) maxRejectedCos = c
            }
        }
        if (maxRejectedCos >= 0.90f) {
            return MemeGateStage1Decision(
                verdict = MemeGateVerdict.REJECTED,
                memeProbability = 0.02f,
                logit = -3.9f,
                lineCount = ocrRegions.size,
                charCount = fullOcrText.length,
                verticalSpacingCv = 1f,
                hasCaptionBand = false,
                receiptDocScore = 0f,
                mundaneUiScore = 0f,
                memeLexicalScore = 0f,
                d1Prompt = "",
                reason = "rejected-by-user-memory",
            )
        }

        // 1. Visual signals against 6,028 OpenVocabConcepts & ordinary-picture negative anchors
        var maxOvHybridCos = 0f
        var maxOvZ = -4f
        val topCandLabels = ArrayList<String>(6)
        if (openVocab != null && imageVec.size == 512) {
            for (e in openVocab.entries) {
                val hc = FloatBlob.dot(e.cHybrid, imageVec)
                if (hc > maxOvHybridCos) maxOvHybridCos = hc
                if (e.isEntity) {
                    val raw = FloatBlob.dot(e.tStock, imageVec)
                    if (raw >= 0.22f) {
                        val z = (raw - e.tagMean) / e.tagStd.coerceAtLeast(0.025f)
                        if (z > maxOvZ) maxOvZ = z
                    }
                }
            }
            for (h in openVocab.retrieveCandidates(imageVec, fullOcrText, limit = 5)) {
                topCandLabels += h.label
            }
        }

        var maxOrdinaryAnchorCos = 0f
        for (anc in curatedAnchors) {
            if (anc.vec.size == imageVec.size) {
                val c = FloatBlob.dot(anc.vec, imageVec)
                if (c > maxOrdinaryAnchorCos) maxOrdinaryAnchorCos = c
            }
        }

        var maxPositiveCos = -2f
        for (lbl in curatedLabels) {
            if (lbl.vec.size == imageVec.size) {
                val c = FloatBlob.dot(lbl.vec, imageVec)
                if (c > maxPositiveCos) maxPositiveCos = c
            }
        }
        val maxCuratedMargin = if (maxPositiveCos > -1.5f) (maxPositiveCos - maxOrdinaryAnchorCos) else 0f

        // 2. OCR bounding-box geometry & spacing regularity
        val centersY = ArrayList<Float>(ocrRegions.size)
        var topBand = false
        var bottomBand = false
        val safeW = width.coerceAtLeast(1).toFloat()
        val safeH = height.coerceAtLeast(1).toFloat()
        for (r in ocrRegions) {
            // Normalize pixel or [0..1] coordinates transparently
            val normW = if (r.width > 1.05f) (r.width / safeW) else r.width
            val normY = if (r.y > 1.05f || r.height > 1.05f) ((r.y + 0.5f * r.height) / safeH) else (r.y + 0.5f * r.height)
            centersY += normY.coerceIn(0f, 1f)
            if (normW >= 0.42f && normY <= 0.25f && r.text.length >= 4) topBand = true
            if (normW >= 0.42f && normY >= 0.75f && r.text.length >= 4) bottomBand = true
        }
        val lineCount = if (ocrRegions.isNotEmpty()) ocrRegions.size
        else if (fullOcrText.isNotBlank()) fullOcrText.lines().count { it.isNotBlank() } else 0
        val charCount = fullOcrText.length
        val hasCaptionBand = topBand || bottomBand

        var spacingCv = 1.0f
        if (centersY.size >= 4) {
            centersY.sort()
            val gaps = ArrayList<Float>()
            for (i in 1 until centersY.size) {
                val g = centersY[i] - centersY[i - 1]
                if (g > 0.005f) gaps += g
            }
            if (gaps.size >= 3) {
                val mean = gaps.sum() / gaps.size
                var varSum = 0f
                for (g in gaps) varSum += (g - mean) * (g - mean)
                varSum /= gaps.size
                spacingCv = if (mean > 1e-4f) sqrt(varSum) / mean else 0f
            }
        }

        // 3. Lexical cues
        val padded = lowerAlnumPadded(fullOcrText)
        val receiptHits = RECEIPT_CUES.count { padded.contains(it) }
        var currencyDigits = 0
        for (i in 0 until fullOcrText.length - 1) {
            val ch = fullOcrText[i]
            if ((ch == '$' || ch == '%') && fullOcrText[i + 1].isDigit()) currencyDigits++
        }
        val receiptDocScore = (
            0.30f * receiptHits + 0.12f * currencyDigits +
                if (lineCount >= 14 && spacingCv < 0.35f) 0.45f else 0f
            ).coerceIn(0f, 1f)

        val uiHits = MUNDANE_UI_CUES.count { padded.contains(it) }
        val mundaneUiScore = (0.32f * uiHits).coerceIn(0f, 1f)
        val memeHits = MEME_LEXICAL_CUES.count { padded.contains(it) }
        val memeLexicalScore = (0.38f * memeHits + if (hasCaptionBand) 0.35f else 0f).coerceIn(0f, 1f)

        // 4. Calibrated multimodal logit
        var logit = -0.55f
        if (maxOvHybridCos >= 0.70f) logit += 5.5f * (maxOvHybridCos - 0.67f)
        if (maxOvZ >= 3.2f) logit += 0.45f * min(4.0f, maxOvZ - 2.6f)
        if (maxCuratedMargin >= 0.11f) logit += 6.5f * (maxCuratedMargin - 0.09f)

        if (maxOrdinaryAnchorCos > 0.22f) {
            val photoExcess = maxOrdinaryAnchorCos - maxOf(0.21f, maxOvHybridCos * 0.50f)
            if (photoExcess > 0f) logit -= 6.4f * photoExcess
        }
        if (maxCuratedMargin <= -0.025f && memeLexicalScore < 0.20f && maxOvHybridCos < 0.70f) {
            logit += 8.0f * (maxCuratedMargin + 0.015f)
        }
        val isTallPhoneUi = isScreenshotHint || (width > 0 && height.toFloat() > width.toFloat() * 1.72f)
        if (hasPanelGutter && detectedPanelCount >= 2 &&
            (!isTallPhoneUi || memeLexicalScore >= 0.35f || maxCuratedMargin >= 0.05f)
        ) {
            logit += 1.05f
        }
        logit += 1.45f * memeLexicalScore
        if (lineCount in 1..6 && charCount in 5..260 && receiptDocScore < 0.25f) logit += 0.55f
        logit -= 2.65f * receiptDocScore
        logit -= 1.65f * mundaneUiScore

        if (isVideo) {
            if (durationMs in 1..30_000L) logit += 0.25f
            else if (durationMs >= 120_000L) logit -= 0.95f
        }

        val prob = sigmoid(logit)
        val verdict = when {
            prob >= 0.70f -> MemeGateVerdict.ACCEPTED
            prob <= 0.24f -> MemeGateVerdict.REJECTED
            charCount >= 12 || (lineCount >= 2 && charCount >= 8) -> MemeGateVerdict.UNCERTAIN_TEXT
            else -> MemeGateVerdict.UNCERTAIN_VISUAL
        }
        val reason = when (verdict) {
            MemeGateVerdict.ACCEPTED -> "stage1-accepted-meme"
            MemeGateVerdict.REJECTED -> if (receiptDocScore >= 0.45f) "stage1-rejected-receipt-or-doc" else "stage1-rejected-ordinary-photo"
            MemeGateVerdict.UNCERTAIN_TEXT -> "stage1-uncertain-text-route-d1"
            MemeGateVerdict.UNCERTAIN_VISUAL -> "stage1-uncertain-visual-route-minicpm"
        }

        val d1Prompt = if (verdict == MemeGateVerdict.UNCERTAIN_TEXT) {
            renderD1Prompt(
                isVideo = isVideo,
                width = width,
                height = height,
                panelCount = detectedPanelCount,
                hasGutter = hasPanelGutter,
                hasCaptionBand = hasCaptionBand,
                lineCount = lineCount,
                stage1Prob = prob,
                ordinaryCos = maxOrdinaryAnchorCos,
                receiptScore = receiptDocScore,
                uiScore = mundaneUiScore,
                candidates = topCandLabels,
                ocrText = fullOcrText,
            )
        } else ""

        return MemeGateStage1Decision(
            verdict = verdict,
            memeProbability = prob,
            logit = logit,
            lineCount = lineCount,
            charCount = charCount,
            verticalSpacingCv = spacingCv,
            hasCaptionBand = hasCaptionBand,
            receiptDocScore = receiptDocScore,
            mundaneUiScore = mundaneUiScore,
            memeLexicalScore = memeLexicalScore,
            d1Prompt = d1Prompt,
            reason = reason,
        )
    }

    fun renderD1Prompt(
        isVideo: Boolean,
        width: Int,
        height: Int,
        panelCount: Int,
        hasGutter: Boolean,
        hasCaptionBand: Boolean,
        lineCount: Int,
        stage1Prob: Float,
        ordinaryCos: Float,
        receiptScore: Float,
        uiScore: Float,
        candidates: List<String>,
        ocrText: String,
    ): String = buildString {
        append("<|startoftext|><|reserved_7|>")
        append("media_kind: ").append(if (isVideo) "video" else "image")
        append("\ndimensions: ").append(width).append('x').append(height)
        append("\npanel_count: ").append(panelCount).append(" (gutter=").append(if (hasGutter) "yes" else "no").append(')')
        append("\ncaption_band: ").append(if (hasCaptionBand) "yes" else "no")
        append("\nocr_lines: ").append(lineCount)
        append("\nvisual_concept_cos: ").append(String.format(Locale.US, "%.3f", stage1Prob))
        append("\nordinary_photo_cos: ").append(String.format(Locale.US, "%.3f", ordinaryCos))
        append("\nreceipt_doc_score: ").append(String.format(Locale.US, "%.3f", receiptScore))
        append("\nmundane_ui_score: ").append(String.format(Locale.US, "%.3f", uiScore))
        if (candidates.isNotEmpty()) {
            append("\nvisual_candidates: ").append(candidates.take(6).joinToString(", ") { OnDeviceDecisionHead.escapeSpecialTokens(it) })
        }
        if (ocrText.isNotBlank()) {
            append("\nocr_text:\n").append(OnDeviceDecisionHead.escapeSpecialTokens(ocrText.take(900)))
        }
        append("<|reserved_8|>")
        append("Decide whether this camera-roll item is an internet meme, reaction image, humorous social screenshot, ")
        append("or shitpost that belongs in a meme library, versus a mundane personal photo, pet/nature/room snapshot, ")
        append("product label, menu, receipt, developer/terminal log, or ordinary app/settings/lockscreen screenshot.")
        append("<|reserved_9|>")
        append("<|mask|> meme: Internet meme, image macro, reaction image, comic panel, ironic shitpost, or funny social post screenshot")
        append("<|reserved_10|><|reserved_9|>")
        append("<|mask|> non_meme: Ordinary camera photo, pet/nature/room snapshot, product box, menu, receipt, dev log, or mundane utility screenshot")
        append("<|reserved_10|><|reserved_11|>")
    }

    /**
     * Parses structured two-domain output (`scene: ... | base_reference: ... | target_topic: ... | reply_intent: ... | tags: ...`)
     * from MiniCPM-V `gateAndDescribe` or Cloud VLM into clean searchable captions, tags, and reply-intent terms.
     * Only an explicit `source_type: movie | source_title: ...` pair or a `base_reference` prefixed
     * with `movie:` / `film:` names a movie here; scene/OCR words and untyped references never establish its identity.
     */
    fun parseTwoDomainDescription(raw: String, sourceTag: String = "vision"): TwoDomainIdentification {
        val cleaned = raw.trim().removePrefix("YES|").removePrefix("YES:").trim()
        val fields = LinkedHashMap<String, String>()
        var hasFields = false
        val chunks = if ('|' in cleaned) cleaned.split('|') else cleaned.lines()
        for (chunk in chunks) {
            val idx = chunk.indexOf(':')
            if (idx in 1..28) {
                hasFields = true
                val key = chunk.substring(0, idx).trim().lowercase(Locale.ROOT).replace(' ', '_')
                val value = chunk.substring(idx + 1).trim()
                if (value.isNotEmpty() && !value.equals("none", ignoreCase = true) && !value.equals("unknown", ignoreCase = true)) {
                    fields[key] = value
                }
            }
        }
        val scene = fields["scene"] ?: fields["description"] ?: fields["caption"]
            ?: cleaned.take(400).takeUnless { hasFields }.orEmpty()
        val movieTitle = fields["source_title"]?.takeIf {
            fields["source_type"]?.lowercase(Locale.ROOT) in setOf(Titles.MOVIE, "film")
        }
        val baseRef = fields["base_reference"] ?: fields["base"] ?: fields["template"] ?: movieTitle.orEmpty()
        val targetTopic = fields["target_topic"] ?: fields["target"] ?: fields["topic"] ?: ""
        val replyIntent = fields["reply_intent"] ?: fields["intent"] ?: fields["situation"] ?: ""
        val rawTags = fields["tags"].orEmpty()

        val tagList = ArrayList<Tag>()
        val seenNorm = HashSet<String>()
        fun pushTag(rawLbl: String, cat: String, score: Float = 0.72f) {
            val norm = TagMerge.normalizeTagLabel(rawLbl)
            if (norm.length < 2 || norm in setOf("none", "unknown", "meme", "image", "photo") || !seenNorm.add(norm)) return
            tagList += Tag(
                label = norm,
                category = cat,
                score = score,
                source = sourceTag,
                scoreDomain = "two-domain",
                confirmation = "unreviewed",
                provenance = "$sourceTag:two-domain",
            )
        }
        movieTitle?.let { pushTag(it, Titles.MOVIE, 0.78f) }
        if (baseRef.isNotBlank()) {
            baseRef.split(',', ';').forEach {
                val explicitTitle = it.trim().takeIf { ref ->
                    ref.startsWith("movie:", ignoreCase = true) || ref.startsWith("film:", ignoreCase = true)
                }?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)
                if (explicitTitle != null) pushTag(explicitTitle, Titles.MOVIE, 0.78f)
                else pushTag(it, "format", 0.78f)
            }
        }
        if (targetTopic.isNotBlank()) {
            targetTopic.split(',', ';').forEach { pushTag(it, "topic", 0.74f) }
        }
        if (rawTags.isNotBlank()) {
            rawTags.split(',', ';').take(8).forEach { pushTag(it, "topic", 0.68f) }
        }

        val combinedCaption = listOfNotNull(
            scene.takeIf { it.isNotBlank() },
            baseRef.takeIf { it.isNotBlank() }?.let { "Reference: $it." },
            targetTopic.takeIf { it.isNotBlank() }?.let { "About: $it." },
            replyIntent.takeIf { it.isNotBlank() }?.let { "Reply use: $it." },
        ).joinToString(" ")

        val terms = listOf(movieTitle.orEmpty(), baseRef, targetTopic, replyIntent, rawTags)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        return TwoDomainIdentification(
            scene = scene,
            baseReference = baseRef,
            targetTopic = targetTopic,
            replyIntent = replyIntent,
            tags = tagList,
            combinedCaption = combinedCaption,
            searchTerms = terms,
        )
    }

    private fun sigmoid(x: Float): Float {
        val c = x.coerceIn(-20f, 20f)
        return (1.0 / (1.0 + exp(-c.toDouble()))).toFloat()
    }

    private fun lowerAlnumPadded(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append(' ')
        var prevSpace = true
        for (ch in raw) {
            if (ch.isLetterOrDigit() || ch == ':' || ch == '>' || ch == '$' || ch == '%') {
                sb.append(ch.lowercaseChar())
                prevSpace = false
            } else if (!prevSpace) {
                sb.append(' ')
                prevSpace = true
            }
        }
        if (!prevSpace) sb.append(' ')
        return sb.toString()
    }
}
