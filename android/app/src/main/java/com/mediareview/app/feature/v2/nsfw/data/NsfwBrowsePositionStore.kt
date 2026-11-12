package com.mediareview.app.feature.v2.nsfw.data

/** 浏览锚点与评分回执分开保存；origin必须为规范化的服务来源。 */
interface NsfwBrowsePositionStore {
    suspend fun loadReviewStatus(origin: String): String? = null
    suspend fun saveReviewStatus(origin: String, status: String?) = Unit
    suspend fun loadGalleryReview(origin: String): NsfwSavedGalleryReview? = null
    suspend fun saveGalleryReview(origin: String, review: NsfwSavedGalleryReview) = Unit
    suspend fun loadPosition(origin: String): String?
    suspend fun loadGalleryAnchor(origin: String): NsfwGalleryAnchor? = null
    suspend fun saveGalleryAnchor(origin: String, anchor: NsfwGalleryAnchor) = Unit
    suspend fun savePosition(origin: String, imageId: String)
}

object EmptyNsfwBrowsePositionStore : NsfwBrowsePositionStore {
    override suspend fun loadPosition(origin: String): String? = null
    override suspend fun savePosition(origin: String, imageId: String) = Unit
}

data class NsfwGalleryAnchor(val imageId: String, val offset: Int = 0)
