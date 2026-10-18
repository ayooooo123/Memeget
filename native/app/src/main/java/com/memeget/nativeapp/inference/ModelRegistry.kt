package com.memeget.nativeapp.inference

internal data class ModelArtifact(
    val id: String,
    val filename: String,
    val url: String,
    val sha256Hex: String? = null,
    val expectedSizeBytes: Long? = null,
)

/** A pinned file the app produces from a pinned download, such as a converted model. */
internal data class DerivedModelArtifact(
    val id: String,
    val filename: String,
    val source: ModelArtifact,
    val sha256Hex: String,
    val expectedSizeBytes: Long,
)

internal object ModelRegistry {
    // Immutable artifact digests + tokenizer + adapter/preprocessing/runtime recipe
    const val PRIMARY_MODEL_FINGERPRINT = "mobileclip-s2@512:5c7311ce:e7576174:8e019f93:recipe-et0.9.2"
    const val DINOV2_MODEL_FINGERPRINT = "dinov2@768:30ead905:recipe-et0.9.2"

    val S2_IMAGE = ModelArtifact(
        id = "mobileclip-s2-image",
        filename = "mobileclip_s2_image_xnnpack_fp32.pte",
        url = "https://github.com/ayooooo123/Memeget/releases/download/models-v1/mobileclip_s2_image_xnnpack_fp32.pte",
        sha256Hex = "5c7311ceeda0238b1fc1df9bf4a179daaff832de41c2bbab36f8fe86a67abd3e",
        expectedSizeBytes = 143424000L,
    )

    val S2_TEXT = ModelArtifact(
        id = "mobileclip-s2-text",
        filename = "mobileclip_s2_text_xnnpack_fp32.pte",
        url = "https://github.com/ayooooo123/Memeget/releases/download/models-v1/mobileclip_s2_text_xnnpack_fp32.pte",
        sha256Hex = "e7576174e756d5579179df4c76ea136123db0a66ee222974a3cf8e2a590ff488",
        expectedSizeBytes = 254061440L,
    )

    val S2_TOKENIZER = ModelArtifact(
        id = "mobileclip-s2-tokenizer",
        filename = "mobileclip_s2_tokenizer.json",
        url = "https://github.com/ayooooo123/Memeget/releases/download/models-v1/mobileclip_s2_tokenizer.json",
        sha256Hex = "8e019f9349caf5f9bb92514b0e4fc00c92841aef6ae8df9d833d975c623b7c72",
        expectedSizeBytes = 3642348L,
    )

    val DINO_MODEL = ModelArtifact(
        id = "dinov2-base-image",
        filename = "dinov2_base_xnnpack_fp32.pte",
        url = "https://github.com/ayooooo123/Memeget/releases/download/models-v1/dinov2_base_xnnpack_fp32.pte",
        sha256Hex = "30ead9054464e2c7c15bed89cb7527d08ea544c930738af91fc32176def78089",
        expectedSizeBytes = 346561280L,
    )

    val OCR_CRAFT = ModelArtifact(
        id = "ocr-craft-detector",
        filename = "craft_xnnpack_int8.pte",
        url = "https://huggingface.co/software-mansion/react-native-executorch-detector-craft/resolve/v0.9.0/xnnpack/craft_xnnpack_int8.pte",
        sha256Hex = "a7cb029596b7222a4e9eb73551df86bae10be39a2f2ddec2d15c4dbcaa4a332a",
        expectedSizeBytes = 20912264L,
    )

    val OCR_CRNN = ModelArtifact(
        id = "ocr-crnn-recognizer-en",
        filename = "crnn_english_xnnpack_fp32.pte",
        url = "https://huggingface.co/software-mansion/react-native-executorch-recognizer-crnn.en/resolve/v0.9.0/english/xnnpack/crnn_english_xnnpack_fp32.pte",
        sha256Hex = "7e44b00c38d5bdd85dcb1962743b83c763e2bc28d8c7ba2f7280e86face35dc8",
        expectedSizeBytes = 18368900L,
    )

    // Whisper small.en, the int8 XNNPACK export react-native-executorch 0.9.2 publishes for this runtime.
    // tiny.en turned a laughing South Park line, "No, dude, no fucking way", into "No, no, no, sucky way.";
    // small.en transcribes it word for word, as whisper large-v3-turbo does. The fp32 export is 2.5 times the
    // size and gave the same text there. The export's decoder holds 128 positions per 29 s window.
    val WHISPER_MODEL = ModelArtifact(
        id = "whisper-small-en-int8",
        filename = "whisper_small_en_xnnpack_int8.pte",
        url = "https://huggingface.co/software-mansion/react-native-executorch-whisper-small.en/resolve/v0.9.0/xnnpack/whisper_small_en_xnnpack_int8.pte",
        sha256Hex = "72d92575cc2248a46a8e2f213a94e5927251e4587edf8e2c57ca14b12c8554a3",
        expectedSizeBytes = 447563136L,
    )

    // One tokenizer serves every English-only Whisper size; the file is identical across them.
    val WHISPER_TOKENIZER = ModelArtifact(
        id = "whisper-en-tokenizer",
        filename = "whisper_en_tokenizer.json",
        url = "https://huggingface.co/software-mansion/react-native-executorch-whisper-small.en/resolve/v0.9.0/tokenizer.json",
        sha256Hex = "5eb60cec1e77aeeb6869a2bb5a8e01a84c3fe5d072d75369343021fe6f5310d0",
        expectedSizeBytes = 2405679L,
    )

    /** Speech model files earlier builds downloaded; removed once the current model is in place. */
    val RETIRED_WHISPER_FILES = listOf("whisper_tiny_en_xnnpack_fp32.pte", "whisper_tiny_en_tokenizer.json")

    val FAST_SAM = ModelArtifact(
        id = "fast-sam-s",
        filename = "fast_sam_s_xnnpack_fp32.pte",
        url = "https://huggingface.co/software-mansion/react-native-executorch-fast-sam/resolve/v0.9.0/s/xnnpack/fast_sam_s_xnnpack_fp32.pte",
        sha256Hex = "ec594c77c0ef0ae72486f24a0d5798902d829807916c91bbd36fe7827a84c140",
        expectedSizeBytes = 47346944L,
    )

    // MiniCPM-V 4.7 1B for local captions (llama.cpp b11588), pinned to openbmb/MiniCPM-V-4.7-1B-gguf@59cd2861.
    val MINICPM_V47_MODEL = ModelArtifact(
        id = "minicpm-v-4.7-1b-q4km",
        filename = "MiniCPM-V-4_7-1B-Q4_K_M.gguf",
        url = "https://huggingface.co/openbmb/MiniCPM-V-4.7-1B-gguf/resolve/59cd286146b6ce70f818508ccf512e11438d8957/MiniCPM-V-4_7-1B-Q4_K_M.gguf",
        sha256Hex = "588c6c94c82f756b86dba35c42ed9ba9ec187d65824eb52f039c347df563e63d",
        expectedSizeBytes = 672003680L,
    )

    val MINICPM_V47_PROJECTOR = ModelArtifact(
        id = "minicpm-v-4.7-1b-mmproj-f16",
        filename = "mmproj-model-f16.gguf",
        url = "https://huggingface.co/openbmb/MiniCPM-V-4.7-1B-gguf/resolve/59cd286146b6ce70f818508ccf512e11438d8957/mmproj-model-f16.gguf",
        sha256Hex = "fb9cc047a3cdad5317fd8433a4aeda2d22f00f02ca4bd93b34ad84165b25a900",
        expectedSizeBytes = 1110117984L,
    )

    // The official projector stores its matrices as BF16, which ARMv8.2 CPUs such as the Pixel 5a's
    // Cortex-A76 only run through a scalar fallback (66-101 s to encode one meme there, 7-9 s as F16).
    // The app converts it once (ProjectorConversion.cpp; 99.97% of values exact, none above 1e-3 changed)
    // and keeps only the converted copy.
    val MINICPM_V47_PROJECTOR_F16 = DerivedModelArtifact(
        id = "minicpm-v-4.7-1b-mmproj-f16-converted",
        filename = "mmproj-model-f16-converted.gguf",
        source = MINICPM_V47_PROJECTOR,
        sha256Hex = "204a7993d345456fb36d19892dbfd4ab6b5ce09026c7bc0bd3fec1e4831834b7",
        expectedSizeBytes = 1110117984L,
    )

    // Fine-tuned LiquidAI/d1-omni-600M decision model (Q8_0 GGUF, llama.cpp b11588 COMMON_DECISION_TYPE_LFM2_D1_OMNI).
    val D1_OMNI_DECISION_MODEL = ModelArtifact(
        id = "d1-omni-600m-memeget-q8_0",
        filename = "d1-omni-600M-memeget-Q8_0.gguf",
        url = "https://github.com/ayooooo123/Memeget/releases/download/models-v1/d1-omni-600M-memeget-Q8_0.gguf",
        sha256Hex = "ed64250aecb7bf62ebd5a655ac68012e9d5af859c968a648184c6f5bcea6d88d",
        expectedSizeBytes = 407203808L,
    )
}
