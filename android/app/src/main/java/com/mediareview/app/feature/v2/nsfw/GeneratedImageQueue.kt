package com.mediareview.app.feature.v2.nsfw

/** 手机端展示模型，不表示上游已提供提示词或增量接口。null与实际空提示词不同。 */
data class GeneratedImage(
    val id: String,
    val createdAt: String?,
    val width: Int?,
    val height: Int?,
    val prompt: String? = null,
    val negativePrompt: String? = null,
    val promptId: String? = null,
    val promptVersionId: String? = null,
    val previewUrl: String? = null,
)

/** 服务端决定增量顺序；客户端只更新原位或追加，不因轮询重排当前浏览窗口。 */
class GeneratedImageQueue(
    items: List<GeneratedImage> = emptyList(),
    currentImageId: String? = null,
) {
    val items: List<GeneratedImage> = items.distinctBy { it.id }.toList()
    val currentImageId: String? = currentImageId?.takeIf { id -> this.items.any { it.id == id } }
        ?: this.items.firstOrNull()?.id
    val currentIndex: Int? get() = currentImageId?.let { id -> items.indexOfFirst { it.id == id }.takeIf { it >= 0 } }

    init { require(this.items.all { it.id.isNotBlank() }) { "图片身份不能为空" } }

    fun append(incoming: List<GeneratedImage>): GeneratedImageQueue {
        val byId = items.associateByTo(linkedMapOf()) { it.id }
        incoming.forEach { image ->
            require(image.id.isNotBlank()) { "图片身份不能为空" }
            byId[image.id] = image
        }
        return GeneratedImageQueue(byId.values.toList(), currentImageId)
    }
}
