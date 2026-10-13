package com.memeget.nativeapp

import org.json.JSONObject

internal enum class CloudProviderType {
    TYPESAFE_SYSTEMONE,
    CLOUDFLARE_CLEF,
    SELFHOST_SYSTEMONE,
}

internal data class CloudConfig(
    val enabled: Boolean = false,
    val provider: CloudProviderType = CloudProviderType.TYPESAFE_SYSTEMONE,
    val typesafeModel: String = "jev-1.13.0",
    val cloudflareAccountId: String = "",
    val cloudflareModel: String = "clef-flash",
    val selfhostUrl: String = "",
    val captionEndpointEnabled: Boolean = false,
    val captionEndpointUrl: String = "",
    val captionModel: String = "gpt-4o-mini",
    val consentTextState: Boolean = false,
    val consentVisualMedia: Boolean = false,
    val consentTranscript: Boolean = false,
    val automaticEscalation: Boolean = false,
    val autoEscalateThreshold: Float = 0.65f,
    val consentTimestamp: Long = 0L,
    val consentRevision: Long = 1L,
    val newItemsAfterId: Long = Long.MAX_VALUE,
    val includeExisting: Boolean = false,
    val unmeteredOnly: Boolean = true,
    val acknowledgeProviderBudget: Boolean = false,
    val dailyRequestLimit: Int = 20,
    val dailyByteLimit: Long = 10L * 1024L * 1024L,
    val dailyCostBudgetUsd: Double = 0.05,
    val hasApiKey: Boolean = false,
    val hasJudgeKey: Boolean = false,
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("enabled", enabled)
        obj.put("provider", provider.name)
        obj.put("typesafeModel", typesafeModel)
        obj.put("cloudflareAccountId", cloudflareAccountId)
        obj.put("cloudflareModel", cloudflareModel)
        obj.put("selfhostUrl", selfhostUrl)
        obj.put("captionEndpointEnabled", captionEndpointEnabled)
        obj.put("captionEndpointUrl", captionEndpointUrl)
        obj.put("captionModel", captionModel)
        obj.put("consentTextState", consentTextState)
        obj.put("consentVisualMedia", consentVisualMedia)
        obj.put("consentTranscript", consentTranscript)
        obj.put("automaticEscalation", automaticEscalation)
        obj.put("autoEscalateThreshold", autoEscalateThreshold.toDouble())
        obj.put("consentTimestamp", consentTimestamp)
        obj.put("consentRevision", consentRevision)
        obj.put("newItemsAfterId", newItemsAfterId)
        obj.put("includeExisting", includeExisting)
        obj.put("unmeteredOnly", unmeteredOnly)
        obj.put("acknowledgeProviderBudget", acknowledgeProviderBudget)
        obj.put("dailyRequestLimit", dailyRequestLimit)
        obj.put("dailyByteLimit", dailyByteLimit)
        obj.put("dailyCostBudgetUsd", dailyCostBudgetUsd)
        obj.put("hasApiKey", hasApiKey)
        obj.put("hasJudgeKey", hasJudgeKey)
        return obj.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): CloudConfig {
            val obj = JSONObject(jsonStr)
            val providerName = obj.optString("provider", CloudProviderType.TYPESAFE_SYSTEMONE.name)
            val provider = try {
                CloudProviderType.valueOf(providerName)
            } catch (_: Exception) {
                CloudProviderType.TYPESAFE_SYSTEMONE
            }
            return CloudConfig(
                enabled = obj.optBoolean("enabled", false),
                provider = provider,
                typesafeModel = obj.optString("typesafeModel", "jev-1.13.0"),
                cloudflareAccountId = obj.optString("cloudflareAccountId", ""),
                cloudflareModel = obj.optString("cloudflareModel", "clef-flash"),
                selfhostUrl = obj.optString("selfhostUrl", ""),
                captionEndpointEnabled = obj.optBoolean("captionEndpointEnabled", false),
                captionEndpointUrl = obj.optString("captionEndpointUrl", ""),
                captionModel = obj.optString("captionModel", "gpt-4o-mini"),
                consentTextState = obj.optBoolean("consentTextState", false),
                consentVisualMedia = obj.optBoolean("consentVisualMedia", false),
                consentTranscript = obj.optBoolean("consentTranscript", false),
                automaticEscalation = obj.optBoolean("automaticEscalation", false),
                autoEscalateThreshold = obj.optDouble("autoEscalateThreshold", 0.65).toFloat(),
                consentTimestamp = obj.optLong("consentTimestamp", 0L),
                consentRevision = obj.optLong("consentRevision", 1L),
                newItemsAfterId = obj.optLong("newItemsAfterId", Long.MAX_VALUE),
                includeExisting = obj.optBoolean("includeExisting", false),
                unmeteredOnly = obj.optBoolean("unmeteredOnly", true),
                acknowledgeProviderBudget = obj.optBoolean("acknowledgeProviderBudget", false),
                dailyRequestLimit = obj.optInt("dailyRequestLimit", 20).coerceIn(1, 500),
                dailyByteLimit = obj.optLong("dailyByteLimit", 10L * 1024L * 1024L).coerceIn(1024L * 1024L, 100L * 1024L * 1024L),
                dailyCostBudgetUsd = obj.optDouble("dailyCostBudgetUsd", 0.05).coerceIn(0.01, 10.0),
                hasApiKey = obj.optBoolean("hasApiKey", false),
                hasJudgeKey = obj.optBoolean("hasJudgeKey", false),
            )
        }
    }
}
