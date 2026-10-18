package com.memeget.nativeapp.storage

import com.memeget.nativeapp.Exemplar
import org.json.JSONArray
import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.data.TagMerge
import org.json.JSONObject

internal object TeachingPackIo {

    const val PACK_FORMAT = "memeget-teaching-pack"
    const val PACK_MODEL = "mobileclip-s2"
    const val PACK_DIM = 512

    const val NATIVE_TEACHING_FORMAT = "memeget-native-teachings"
    const val NATIVE_TEACHING_VERSION = 1
    fun parseTeachingPack(jsonText: String): List<Exemplar> {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            throw IllegalArgumentException("Not a valid teaching pack (could not read the file as JSON)")
        }

        val format = root.optString("format")
        if (format != PACK_FORMAT) {
            throw IllegalArgumentException("This file isn’t a Memeget teaching pack")
        }

        val model = root.optString("model")
        val dim = root.optInt("dim", 0)
        if (model != PACK_MODEL || dim != PACK_DIM) {
            throw IllegalArgumentException("Pack was made with a different model and can’t be used here")
        }

        val exemplarsArray = root.optJSONArray("exemplars")
            ?: throw IllegalArgumentException("Teaching pack has no examples")

        val result = mutableListOf<Exemplar>()
        for (i in 0 until exemplarsArray.length()) {
            val item = exemplarsArray.optJSONObject(i) ?: continue
            val label = item.optString("label").trim()
            if (label.isBlank()) continue

            val vecArray = item.optJSONArray("vector") ?: continue
            if (vecArray.length() != PACK_DIM) continue

            val floats = FloatArray(PACK_DIM)
            var valid = true
            for (j in 0 until PACK_DIM) {
                val num = vecArray.optDouble(j, Double.NaN)
                if (num.isNaN() || num.isInfinite()) {
                    valid = false
                    break
                }
                floats[j] = num.toFloat()
            }
            if (!valid) continue

            val category = item.optString("category").ifBlank { "character" }
            val positive = item.optBoolean("positive", true)

            val associationsList = mutableListOf<String>()
            val assocArray = item.optJSONArray("associations")
            if (assocArray != null) {
                for (k in 0 until assocArray.length()) {
                    val s = assocArray.optString(k).trim()
                    if (s.isNotBlank()) associationsList.add(s)
                }
            }

            result.add(
                Exemplar(
                    id = 0L,
                    label = label,
                    category = category,
                    vector = floats,
                    positive = positive,
                    associations = associationsList,
                    sourceUri = "",
                    model = PACK_MODEL,
                    origin = "pack",
                    pack = root.optString("name").trim()
                )
            )
        }

        if (result.isEmpty()) {
            throw IllegalArgumentException("Teaching pack had no usable examples")
        }

        return result
    }

    fun serializeNativeTeachings(
        exemplars: List<Exemplar>,
        name: String = "",
        author: String = "",
        createdAt: Long = System.currentTimeMillis()
    ): String {
        val root = JSONObject()
        root.put("format", NATIVE_TEACHING_FORMAT)
        root.put("version", NATIVE_TEACHING_VERSION)
        root.put("name", name.trim().take(60))
        root.put("author", author.trim().take(60))
        root.put("createdAt", createdAt)
        root.put("count", exemplars.size)

        val exemplarsArray = JSONArray()
        for (e in exemplars) {
            val item = JSONObject()
            item.put("id", e.id)
            item.put("label", e.label)
            item.put("category", e.category)
            item.put("positive", e.positive)
            item.put("model", e.model)
            item.put("origin", e.origin)
            item.put("pack", e.pack)
            item.put("sourceUri", e.sourceUri)

            val vecArray = JSONArray()
            for (f in e.vector) {
                vecArray.put(f.toDouble())
            }
            item.put("vector", vecArray)

            val assocArray = JSONArray()
            for (a in e.associations) {
                assocArray.put(a)
            }
            item.put("associations", assocArray)

            exemplarsArray.put(item)
        }
        root.put("exemplars", exemplarsArray)
        return root.toString()
    }

    fun parseNativeTeachings(jsonText: String): List<Exemplar> {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            throw IllegalArgumentException("Not a valid teaching pack (could not read the file as JSON)")
        }

        if (root.optString("format") != NATIVE_TEACHING_FORMAT) {
            return parseTeachingPack(jsonText)
        }
        require(root.optInt("version", -1) == NATIVE_TEACHING_VERSION) {
            "Unsupported native teaching format version"
        }

        val exemplarsArray = root.optJSONArray("exemplars") ?: return emptyList()
        val result = mutableListOf<Exemplar>()
        for (i in 0 until exemplarsArray.length()) {
            val item = exemplarsArray.optJSONObject(i) ?: continue
            val label = item.optString("label").trim()
            if (label.isBlank()) continue
            val model = item.optString("model").trim()
            require(model.isNotBlank()) { "Native teaching is missing its model fingerprint" }

            val vecArray = item.optJSONArray("vector") ?: continue
            val floats = FloatArray(vecArray.length())
            var valid = true
            for (j in 0 until vecArray.length()) {
                val num = vecArray.optDouble(j, Double.NaN)
                if (num.isNaN() || num.isInfinite()) {
                    valid = false
                    break
                }
                floats[j] = num.toFloat()
            }
            if (!valid || floats.isEmpty()) continue

            val associationsList = mutableListOf<String>()
            val assocArray = item.optJSONArray("associations")
            if (assocArray != null) {
                for (k in 0 until assocArray.length()) {
                    val s = assocArray.optString(k).trim()
                    if (s.isNotBlank()) associationsList.add(s)
                }
            }

            result.add(
                Exemplar(
                    id = item.optLong("id", 0L),
                    label = label,
                    category = item.optString("category", "character"),
                    vector = floats,
                    positive = item.optBoolean("positive", true),
                    associations = associationsList,
                    sourceUri = item.optString("sourceUri", ""),
                    model = model,
                    origin = item.optString("origin", "self"),
                    pack = item.optString("pack", "")
                )
            )
        }
        return result
    }

    /**
     * Optional unreviewed machine tags carried alongside a native teaching file. Always parsed with
     * confirmation="unreviewed" and source="propagated" so model predictions never enter exemplars or
     * override a person's confirmed tags or rejections.
     */
    fun parseNativeMachineTags(jsonText: String): Map<Long, List<Tag>> {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return emptyMap()
        }
        if (root.optString("format") != NATIVE_TEACHING_FORMAT) return emptyMap()
        val arr = root.optJSONArray("machineTags") ?: return emptyMap()
        val out = LinkedHashMap<Long, List<Tag>>()
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val id = entry.optLong("id", -1L)
            if (id <= 0L) continue
            val tagsArr = entry.optJSONArray("tags") ?: continue
            val list = ArrayList<Tag>()
            for (j in 0 until tagsArr.length()) {
                val t = tagsArr.optJSONObject(j) ?: continue
                val label = TagMerge.normalizeTagLabel(t.optString("label"))
                if (label.isEmpty()) continue
                val score = t.optDouble("score", 0.85).toFloat().coerceIn(0f, 1f)
                list += Tag(
                    label = label,
                    category = t.optString("category", "topic").ifBlank { "topic" },
                    score = score,
                    source = "propagated",
                    scoreDomain = t.optString("scoreDomain", "d1-omni-600m-r2").ifBlank { "d1-omni-600m-r2" },
                    confirmation = "unreviewed",
                    provenance = t.optString("provenance", "d1-omni-600m-r2").ifBlank { "d1-omni-600m-r2" },
                )
            }
            if (list.isNotEmpty()) out[id] = list
        }
        return out
    }
}
