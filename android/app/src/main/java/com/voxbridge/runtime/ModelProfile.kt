package com.voxbridge.runtime

/** Small, explicit runtime model profile. Drive/Colab paths never enter runtime code. */
data class ModelProfile(
    val id: String,
    val asrModels: Map<String, String>,
    val mtBackend: String = "envit5",
    val qnnAsr: Boolean = false,
) {
    init {
        require(asrModels.keys.containsAll(listOf("vi", "en"))) {
            "Model profile $id must define vi and en ASR models"
        }
    }

    companion object {
        val VI_EN_QNN_ENVIT5 = ModelProfile(
            "vi-en-qnn-envit5", mapOf("vi" to "phowhisper-small", "en" to "parakeet-v2"),
            mtBackend = "envit5", qnnAsr = true,
        )
        /** User-confirmed Phase-3 Vietnamese ASR; English model remains unchanged. */
        val VI_EN_PHO_SMALL = ModelProfile(
            id = "vi-en-phosmall",
            asrModels = mapOf("vi" to "phowhisper-small", "en" to "whisper-small"),
        )
        /** Final Phase-2 production selection from the submitted technical proposal. */
        val VI_EN_PHASE2 = ModelProfile(
            id = "vi-en-phase2",
            asrModels = mapOf("vi" to "phowhisper-base", "en" to "whisper-small"),
        )
        val VI_EN_BASE = ModelProfile(
            id = "vi-en-base",
            asrModels = mapOf("vi" to "phowhisper-base", "en" to "whisper-base"),
        )
        val VI_EN_TINY = ModelProfile(
            id = "vi-en-tiny",
            asrModels = mapOf("vi" to "whisper-tiny", "en" to "whisper-tiny"),
        )
        val VI_EN_SMALL = ModelProfile(
            id = "vi-en-small",
            asrModels = mapOf("vi" to "whisper-small", "en" to "whisper-small"),
        )

        private val ALL = listOf(VI_EN_QNN_ENVIT5, VI_EN_PHO_SMALL, VI_EN_PHASE2, VI_EN_BASE, VI_EN_TINY, VI_EN_SMALL).associateBy { it.id }

        fun fromId(id: String): ModelProfile = ALL[id]
            ?: error("Unknown model profile '$id'. Available: ${ALL.keys.sorted().joinToString()}")
    }
}
