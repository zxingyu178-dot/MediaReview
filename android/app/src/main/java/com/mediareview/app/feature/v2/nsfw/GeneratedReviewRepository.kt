package com.mediareview.app.feature.v2.nsfw

/** 客户端领域合同；HTTP映射在上游合同冻结后接入，不推测生产端点。 */
data class GeneratedCapabilities(
    val incrementalFeed: Boolean = false,
    val details: Boolean = false,
    val promptScoring: Boolean = false,
)

data class GeneratedPage(
    val items: List<GeneratedImage>,
    val nextCursor: String?,
    val watermark: String?,
)

enum class PromptDimension { SUBJECT, CLOTHING, SCENE, COMPOSITION, LIGHTING, STYLE, QUALITY }

data class PromptTarget(val promptId: String, val versionId: String?, val overallPrompt: Boolean = false) {
    init {
        require(promptId.isNotBlank()) { "缺少提示词归属，无法评分" }
        require((versionId != null && versionId.isNotBlank() && !overallPrompt) ||
            (versionId == null && overallPrompt)) { "请明确选择版本评分或提示词整体评分" }
    }
}

data class PromptEvaluation(
    val target: PromptTarget,
    val overall: Int,
    val dimensions: Map<PromptDimension, Int> = emptyMap(),
    val notes: String? = null,
    val requestId: String = java.util.UUID.randomUUID().toString(),
) {
    init {
        require(overall in 1..5 && dimensions.values.all { it in 1..5 }) { "评分范围为1至5分" }
        require(requestId.isNotBlank()) { "评分请求身份不能为空" }
    }
}

interface GeneratedReviewRepository {
    suspend fun capabilities(): GeneratedCapabilities
    /** cursor为历史分页，after为追平后的增量水位；互斥。 */
    suspend fun feed(cursor: String? = null, after: String? = null): GeneratedPage
    suspend fun detail(id: String): GeneratedImage
    suspend fun evaluation(target: PromptTarget): PromptEvaluation?
    /** 仅用户显式提交一次；超时必须查询历史核对，不自动重发追加评分。 */
    suspend fun saveEvaluation(evaluation: PromptEvaluation)
}

class GeneratedCursorExpired : Exception()

class UnavailableGeneratedRepository : GeneratedReviewRepository {
    override suspend fun capabilities() = GeneratedCapabilities()
    override suspend fun feed(cursor: String?, after: String?): GeneratedPage = unavailable()
    override suspend fun detail(id: String): GeneratedImage = unavailable()
    override suspend fun evaluation(target: PromptTarget): PromptEvaluation? = unavailable()
    override suspend fun saveEvaluation(evaluation: PromptEvaluation): Unit = unavailable()
    private fun unavailable(): Nothing = throw IllegalStateException("当前服务尚未提供此能力")
}
