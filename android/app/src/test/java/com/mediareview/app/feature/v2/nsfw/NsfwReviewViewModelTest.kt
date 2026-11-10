package com.mediareview.app.feature.v2.nsfw

import androidx.lifecycle.SavedStateHandle
import com.mediareview.app.feature.v2.nsfw.data.NsfwApiException
import com.mediareview.app.feature.v2.nsfw.data.NsfwConnectionSnapshot
import com.mediareview.app.feature.v2.nsfw.data.NsfwDecisionResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwErrorCodes
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationReceipt
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationRequest
import com.mediareview.app.feature.v2.nsfw.data.NsfwPairResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwPendingEvaluation
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptImagesPage
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NsfwReviewViewModelTest {

    @Test fun comparisonScoreUsesFrozenVersionInsteadOfCurrentImage() = runTest {
        val model = bootstrapPaired(details = nsfwDetails("img_a", promptId = "other", versionId = "other-v", linkState = "EXACT"))
        val scope = NsfwPromptComparison.open(com.mediareview.app.feature.v2.nsfw.data.NsfwPromptLink("p", "v", "EXACT"), com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryFilter())
        model.openComparisonScore(scope)
        assertEquals("p", model.state.value.panel!!.promptId)
        assertEquals("v", model.state.value.panel!!.versionId)
        assertEquals("v", model.state.value.panel!!.summary!!.promptVersionId)
        model.submitEvaluation(4, emptyMap(), "isolated comparison")
        assertEquals("v", model.state.value.panel!!.submitted!!.promptVersionId)
    }

    @Test fun promptUpdatesRefreshSummaryWithoutReplacingDraftIdentityOrImages() = runTest {
        val model = bootstrapPaired()
        var count = 1
        var imageReads = 0
        data.summaryResponder = { id, version -> NsfwPromptSummary(promptId = id, promptVersionId = version, evaluationCount = count) }
        data.promptImagesResponder = { _, _ -> imageReads++; NsfwPromptImagesPage() }
        model.openPromptPanel()
        val original = model.state.value.panel!!
        count = 2
        model.checkPromptUpdates()
        val updated = model.state.value.panel!!
        assertEquals(2, updated.summary!!.evaluationCount)
        assertEquals(original.requestId, updated.requestId)
        assertEquals(original.pendingRequest, updated.pendingRequest)
        assertEquals(1, imageReads)
        assertTrue(updated.remoteUpdated)
    }

    @Test fun promptUpdatesLateResponseCannotReplaceClosedAndReopenedPanel() = runTest {
        val model = bootstrapPaired()
        model.openPromptPanel()
        val old = model.state.value.panel!!
        val deferred = CompletableDeferred<NsfwPromptSummary>()
        data.summaryResponder = { _, _ -> deferred.await() }
        model.checkPromptUpdates()
        model.closePromptPanel()
        data.summaryResponder = { id, version -> NsfwPromptSummary(promptId = id, promptVersionId = version, evaluationCount = 3) }
        model.openPromptPanel()
        val reopened = model.state.value.panel!!
        assertFalse(old.requestId == reopened.requestId)
        deferred.complete(NsfwPromptSummary(promptId = old.promptId, promptVersionId = old.versionId, evaluationCount = 99))
        assertEquals(reopened.requestId, model.state.value.panel!!.requestId)
        assertEquals(3, model.state.value.panel!!.summary!!.evaluationCount)
    }

    @Test fun gallerySearchRequiresSearchCapabilityEvenWhenOtherFiltersAreSupported() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8877", paired = true,
            capabilities = setOf("mobile_gallery_query_v1", "mobile_gallery_filters_v1"))
        data.galleryResponder = { bidirectionalPage(listOf("a"), 0) }
        val model = vm()
        val before = model.state.value.galleryQuery
        val calls = data.galleryCalls.size
        model.changeGalleryFilter(com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryFilter(search = "needle"))
        assertEquals(calls, data.galleryCalls.size)
        assertEquals(before, model.state.value.galleryQuery)
        assertTrue(model.state.value.message!!.contains("搜索"))
    }

    @Test fun gallerySearchUnknownMembershipInvalidatesOrdinalWithoutRemovingImage() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8877", paired = true,
            capabilities = setOf("mobile_gallery_query_v1", "mobile_gallery_filters_v1", "mobile_gallery_search_v1", "mobile_gallery_bidirectional_v1"))
        data.galleryResponder = { bidirectionalPage(listOf("a"), 0) }
        val model = vm()
        model.changeGalleryFilter(com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryFilter(search = "needle"))
        assertTrue(model.state.value.galleryPositionValid)
        data.feedResponder = { nsfwPage(listOf(nsfwItem("a", revision = "2").copy(eventType = "IMAGE_REVIEW_UPDATED")), nextCursor = "next") }
        model.refresh()
        assertEquals(listOf("a"), model.state.value.items.map { it.imageId })
        assertTrue(model.state.value.galleryMembershipUnknown)
        assertFalse(model.state.value.galleryPositionValid)
    }

    private fun bidirectionalPage(ids: List<String>, start: Int, previous: String? = null, next: String? = null): com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryPage {
        val body = """{"items":[${ids.joinToString(",") { """{"image_id":"$it","revision":"1"}""" }}],"start_index":$start,"total":4,"previous_page_cursor":${previous?.let { "\"$it\"" } ?: "null"},"next_page_cursor":${next?.let { "\"$it\"" } ?: "null"},"has_more":${next != null},"sync_cursor":"w","scope_cursor":"s"}"""
        return Json { ignoreUnknownKeys = true }.decodeFromString(body)
    }

    private fun enableBidirectionalGallery() {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8877", paired = true,
            capabilities = setOf("mobile_gallery_query_v1", "mobile_gallery_bidirectional_v1"))
    }

    @Test fun galleryBidirectionalPreviousLoadsNearestPageAndPreservesNextCursor() = runTest {
        enableBidirectionalGallery()
        val before = CompletableDeferred<com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryPage>()
        data.galleryResponder = { call -> when (call.page) {
            "back" -> before.await()
            "forward" -> bidirectionalPage(listOf("d"), 3, previous = "before-d")
            else -> bidirectionalPage(listOf("b", "c"), 1, previous = "back", next = "forward")
        } }
        val model = vm()
        model.select("b")
        assertEquals("2 / 4", model.state.value.reviewPositionLabel)
        model.previous()
        assertEquals("back", data.galleryCalls.last().page)
        assertEquals("b", model.state.value.currentId)
        before.complete(bidirectionalPage(listOf("a"), 0, next = "after-a"))
        assertEquals("a", model.state.value.currentId)
        assertEquals("1 / 4", model.state.value.reviewPositionLabel)
        assertEquals(listOf("a", "b", "c"), model.state.value.items.map { it.imageId })
        model.next(); model.next(); model.next()
        assertEquals("forward", data.galleryCalls.last().page)
        assertEquals("d", model.state.value.currentId)
        assertEquals("4 / 4", model.state.value.reviewPositionLabel)
        assertEquals(listOf("a", "b", "c", "d"), model.state.value.items.map { it.imageId })
        assertEquals(0, data.feedCalls)
    }

    @Test fun galleryBidirectionalPreviousFailureKeepsImageAndCanRetry() = runTest {
        enableBidirectionalGallery()
        var attempts = 0
        data.galleryResponder = { call ->
            if (call.page == "back") {
                attempts++
                if (attempts == 1) throw java.io.IOException("临时断网")
                bidirectionalPage(listOf("a"), 0, next = "after-a")
            } else bidirectionalPage(listOf("b", "c"), 1, previous = "back", next = "forward")
        }
        val model = vm()
        model.select("b")
        model.previous()
        assertEquals(1, attempts)
        assertEquals("b", model.state.value.currentId)
        assertTrue(model.state.value.message.orEmpty().contains("临时断网"))
        model.previous()
        assertEquals(2, attempts)
        assertEquals("a", model.state.value.currentId)
    }

    @Test fun galleryBidirectionalLatePreviousCannotChangeNewScopeOrPairing() = runTest {
        enableBidirectionalGallery()
        val release = CompletableDeferred<Unit>()
        data.galleryResponder = { call -> when {
            call.page == "back" -> {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                throw NsfwApiException("UNAUTHORIZED", "旧前页401", 401)
            }
            call.query.filter.sort == com.mediareview.app.feature.v2.nsfw.data.NsfwGallerySort.OLDEST ->
                bidirectionalPage(listOf("fresh"), 0).copy(scopeCursor = "fresh", sort = "created_asc")
            else -> bidirectionalPage(listOf("b", "c"), 1, previous = "back", next = "forward")
        } }
        val model = vm()
        model.previous()
        assertEquals("back", data.galleryCalls.last().page)
        model.changeGalleryFilter(com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryFilter(sort = com.mediareview.app.feature.v2.nsfw.data.NsfwGallerySort.OLDEST))
        release.complete(Unit)
        assertEquals("fresh", model.state.value.currentId)
        assertEquals(NsfwPhase.READY, model.state.value.phase)
        assertEquals(0, gateway.authRejectedCalls)
    }

    @Test fun galleryFiltersOldBridgeRejectsScopeWithoutFetchingOrClearingGallery() = runTest {
        enableBidirectionalGallery()
        data.galleryResponder = { bidirectionalPage(listOf("a"), 0) }
        val model = vm()
        val query = model.state.value.galleryQuery
        val calls = data.galleryCalls.size
        val items = model.state.value.items
        model.changeGalleryFilter(com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryFilter(status = "NEW"))
        assertEquals(calls, data.galleryCalls.size)
        assertEquals(query, model.state.value.galleryQuery)
        assertEquals(items, model.state.value.items)
        assertTrue(model.state.value.message!!.contains("不支持"))
    }

    @Test fun galleryViewLatestSwitchesOldestToNewestAndKeepsDates() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8877", paired = true, capabilities = setOf("mobile_gallery_query_v1"))
        data.galleryResponder = { call -> com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryPage(items = emptyList(), syncCursor = "watermark", scopeCursor = "scope", sort = call.query.filter.sort.wire) }
        val model = vm()
        model.changeGalleryFilter(com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryFilter(sort = com.mediareview.app.feature.v2.nsfw.data.NsfwGallerySort.OLDEST, dates = com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryDates.TODAY))
        model.viewLatestImages()
        assertEquals(com.mediareview.app.feature.v2.nsfw.data.NsfwGallerySort.NEWEST, data.galleryCalls.last().query.filter.sort)
        assertEquals(com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryDates.TODAY, data.galleryCalls.last().query.filter.dates)
    }

    @Test fun galleryOldDetailSuccessCannotOverwriteNewQuery() = runTest { assertOldGalleryDetailIgnored(false) }
    @Test fun galleryOldDetailAuthFailureCannotClearCurrentPairing() = runTest { assertOldGalleryDetailIgnored(true) }

    private suspend fun assertOldGalleryDetailIgnored(authError: Boolean) {
        val release = CompletableDeferred<Unit>()
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8877", paired = true, capabilities = setOf("mobile_gallery_query_v1"))
        data.galleryResponder = { call -> com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryPage(items = listOf(nsfwItem("a")), syncCursor = "watermark", scopeCursor = call.query.filter.sort.wire) }
        data.detailsResponder = { id ->
            release.await()
            if (authError) throw NsfwApiException("UNAUTHORIZED", "旧详情的401", 401)
            nsfwDetails(id)
        }
        val model = vm()
        model.select("a")
        assertTrue(model.state.value.detailLoading)
        model.changeGalleryFilter(com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryFilter(sort = com.mediareview.app.feature.v2.nsfw.data.NsfwGallerySort.OLDEST))
        release.complete(Unit)
        assertNull(model.state.value.detail)
        assertEquals(NsfwPhase.READY, model.state.value.phase)
        assertEquals(0, gateway.authRejectedCalls)
    }

    @Test fun galleryLateQueryCannotReplaceNewFilterOrClearItsLoading() = runTest {
        val old = CompletableDeferred<com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryPage>()
        val newest = CompletableDeferred<com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryPage>()
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8877", paired = true, capabilities = setOf("mobile_gallery_query_v1"))
        data.galleryResponder = { call ->
            when {
                call.query.filter.sort == com.mediareview.app.feature.v2.nsfw.data.NsfwGallerySort.OLDEST -> kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { old.await() }
                call.query.filter.dates == com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryDates.TODAY -> newest.await()
                else -> com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryPage(items = listOf(nsfwItem("initial")), syncCursor = "w", scopeCursor = "initial")
            }
        }
        val model = vm()
        model.changeGalleryFilter(com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryFilter(sort = com.mediareview.app.feature.v2.nsfw.data.NsfwGallerySort.OLDEST))
        model.changeGalleryFilter(com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryFilter(dates = com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryDates.TODAY))
        old.complete(com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryPage(items = listOf(nsfwItem("stale")), syncCursor = "old", scopeCursor = "old"))
        assertTrue(model.state.value.syncing)
        assertTrue(model.state.value.items.isEmpty())
        newest.complete(com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryPage(items = listOf(nsfwItem("today")), syncCursor = "new", scopeCursor = "new"))
        assertEquals(listOf("today"), model.state.value.items.map { it.imageId })
        assertEquals(com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryDates.TODAY, model.state.value.galleryQuery.filter.dates)
        assertFalse(model.state.value.syncing)
    }

    @Test fun galleryUsesLatestQueryAndSeparatePagingAndSyncCursors() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8877", paired = true, capabilities = setOf("mobile_gallery_query_v1"))
        data.galleryResponder = { call -> com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryPage(
            items = listOf(nsfwItem(if (call.page == null) "new" else "older")),
            nextPageCursor = if (call.page == null) "page2" else null,
            hasMore = call.page == null, syncCursor = "events10", scopeCursor = "scope1",
        ) }
        val model = vm()
        assertEquals(listOf("new"), model.state.value.items.map { it.imageId })
        assertEquals(0, data.feedCalls)
        model.loadMore()
        assertEquals("page2", data.galleryCalls.last().page)
        assertEquals(listOf("new", "older"), model.state.value.items.map { it.imageId })
        data.feedResponder = { nsfwPage(emptyList(), nextCursor = "events11") }
        model.refresh()
        assertEquals("events10", data.lastFeedCursor)
        assertNull(model.state.value.message)
    }

    @Test fun galleryFiltersSyncRemovesOutOfScopeAndCountsOnlyMatchingNewImages() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8877", paired = true,
            capabilities = setOf("mobile_gallery_query_v1", "mobile_gallery_filters_v1", "mobile_gallery_bidirectional_v1"))
        data.galleryResponder = { bidirectionalPage(listOf("a", "b"), 0) }
        val model = vm()
        model.changeGalleryFilter(com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryFilter(status = "NEW", category = "anime"))
        data.feedResponder = { nsfwPage(listOf(
            nsfwItem("a", revision = "2", status = "KEEP").copy(eventType = "IMAGE_REVIEW_UPDATED", categories = setOf("anime")),
            nsfwItem("outside").copy(eventType = "IMAGE_CREATED", createdAt = "2026-11-10T00:00:00Z", categories = setOf("imported")),
            nsfwItem("inside").copy(eventType = "IMAGE_CREATED", createdAt = "2026-11-10T00:00:00Z", categories = setOf("anime")),
        ), nextCursor = "w2") }
        model.refresh()
        assertEquals(listOf("b"), model.state.value.items.map { it.imageId })
        assertEquals(1, model.state.value.newImageCount)
        assertFalse(model.state.value.galleryPositionValid)
    }

    @Test fun galleryNewImagesOnlyNotifyAndReviewUpdatesStayInPlace() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8877", paired = true, capabilities = setOf("mobile_gallery_query_v1"))
        data.galleryResponder = { com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryPage(items = listOf(nsfwItem("a"), nsfwItem("b")), syncCursor = "w1", scopeCursor = "s1") }
        val model = vm()
        model.select("a")
        data.feedResponder = { nsfwPage(listOf(
            nsfwItem("a", revision = "2", status = "KEEP").copy(eventType = "IMAGE_REVIEW_UPDATED"),
            nsfwItem("c").copy(eventType = "IMAGE_CREATED", createdAt = "2026-11-09T00:00:00.000Z"),
            nsfwItem("old-unloaded").copy(eventType = "IMAGE_FAVORITE_UPDATED"),
        ), nextCursor = "w2") }
        model.refresh()
        assertEquals(listOf("a", "b"), model.state.value.items.map { it.imageId })
        assertEquals("a", model.state.value.currentId)
        assertEquals("KEEP", model.state.value.items.first().status)
        assertEquals(1, model.state.value.newImageCount)
    }

    @Test fun galleryResumeUsesSavedScopeWithoutReplayingHistory() = runTest {
        val positions = MemoryBrowsePositions()
        val initialPage = CompletableDeferred<Unit>()
        val query = com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryFilter(sort = com.mediareview.app.feature.v2.nsfw.data.NsfwGallerySort.OLDEST).resolve()
        positions.reviews["http://nsfw.test:8877"] = com.mediareview.app.feature.v2.nsfw.data.NsfwSavedGalleryReview(query, "old-scope", "old")
        positions.anchors["http://nsfw.test:8877"] = com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryAnchor("old", 37)
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8877", paired = true, capabilities = setOf("mobile_gallery_query_v1"))
        data.galleryResponder = { call ->
            if (call.scope == null) initialPage.await()
            com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryPage(
            items = listOf(nsfwItem(call.anchor ?: "new")), syncCursor = "watermark", scopeCursor = call.scope ?: "fresh", sort = call.query.filter.sort.wire,
        ) }
        val model = vm(positions = positions)
        // 新范围首屏尚在加载时，也不能让界面记住旧范围的滚动锚点。
        assertNull(model.state.value.galleryAnchor)
        initialPage.complete(Unit)
        assertEquals("new", model.state.value.currentId)
        model.resumeGalleryReview()
        assertEquals("old-scope", data.galleryCalls.last().scope)
        assertEquals("old", data.galleryCalls.last().anchor)
        assertEquals(query, data.galleryCalls.last().query)
        assertEquals("old", model.state.value.currentId)
        assertEquals(0, data.feedCalls)
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var data: FakeNsfwDataSource
    private lateinit var gateway: FakePairingGateway

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        data = FakeNsfwDataSource()
        gateway = FakePairingGateway()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        savedState: SavedStateHandle = SavedStateHandle(),
        journal: FakeNsfwEvaluationJournal = FakeNsfwEvaluationJournal(),
        positions: com.mediareview.app.feature.v2.nsfw.data.NsfwBrowsePositionStore = com.mediareview.app.feature.v2.nsfw.data.EmptyNsfwBrowsePositionStore,
    ): NsfwReviewViewModel =
        NsfwReviewViewModel(
            data,
            gateway,
            savedState,
            Json { ignoreUnknownKeys = true },
            journal,
            positions,
        )

    private fun bootstrapPaired(
        page: com.mediareview.app.feature.v2.nsfw.data.NsfwFeedPage =
            nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_b"))),
        details: com.mediareview.app.feature.v2.nsfw.data.NsfwImageDetails = nsfwDetails("img_a"),
        savedState: SavedStateHandle = SavedStateHandle(),
        journal: FakeNsfwEvaluationJournal = FakeNsfwEvaluationJournal(),
        positions: com.mediareview.app.feature.v2.nsfw.data.NsfwBrowsePositionStore = com.mediareview.app.feature.v2.nsfw.data.EmptyNsfwBrowsePositionStore,
    ): NsfwReviewViewModel {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { page }
        data.detailsResponder = { id -> details.copy(imageId = id) }
        return vm(savedState, journal, positions)
    }

    private class MemoryBrowsePositions : com.mediareview.app.feature.v2.nsfw.data.NsfwBrowsePositionStore {
        val reviews = mutableMapOf<String, com.mediareview.app.feature.v2.nsfw.data.NsfwSavedGalleryReview>()
        override suspend fun loadGalleryReview(origin: String) = reviews[origin]
        override suspend fun saveGalleryReview(origin: String, review: com.mediareview.app.feature.v2.nsfw.data.NsfwSavedGalleryReview) { reviews[origin] = review }
        val values = mutableMapOf<String, String>()
        val anchors = mutableMapOf<String, com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryAnchor>()
        var failGalleryLoad = false
        override suspend fun loadGalleryAnchor(origin: String): com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryAnchor? {
            if (failGalleryLoad) throw java.io.IOException("isolated anchor read failure")
            return anchors[origin]
        }
        override suspend fun saveGalleryAnchor(origin: String, anchor: com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryAnchor) { anchors[origin] = anchor }
        override suspend fun loadPosition(origin: String) = values[origin]
        override suspend fun savePosition(origin: String, imageId: String) { values[origin] = imageId }
    }

    @Test fun restoresLastBrowsedImageAfterViewModelRecreation() = runTest {
        val positions = MemoryBrowsePositions().apply { values["http://nsfw.test:8767"] = "img_b" }
        val model = bootstrapPaired(positions = positions)
        assertEquals("img_b", model.state.value.currentId)
    }

    @Test fun selectingImagePersistsBrowseIdentity() = runTest {
        val positions = MemoryBrowsePositions()
        val model = bootstrapPaired(positions = positions)
        model.select("img_b")
        assertEquals("img_b", positions.values["http://nsfw.test:8767"])
    }

    @Test fun pendingBrowseTargetSurvivesLaterPageFailureAndLoadsLater() = runTest {
        val positions = MemoryBrowsePositions().apply { values["http://nsfw.test:8767"] = "img_b" }
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { cursor ->
            if (cursor == null) nsfwPage(listOf(nsfwItem("img_a")), "next", true)
            else throw java.io.IOException("isolated feed failure")
        }
        val model = vm(positions = positions)
        assertNull(model.state.value.currentId)
        assertTrue(model.state.value.restoringBrowsePosition)
        assertEquals("img_b", positions.values["http://nsfw.test:8767"])
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_b"))) }
        model.loadMore()
        assertEquals("img_b", model.state.value.currentId)
        assertFalse(model.state.value.restoringBrowsePosition)
    }

    @Test fun manualSelectionCancelsPendingBrowseRestore() = runTest {
        val positions = MemoryBrowsePositions().apply { values["http://nsfw.test:8767"] = "img_b" }
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { cursor ->
            if (cursor == null) nsfwPage(listOf(nsfwItem("img_a")), "next", true)
            else throw java.io.IOException("isolated feed failure")
        }
        val model = vm(positions = positions)
        model.select("img_a")
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_b"))) }
        model.loadMore()
        assertEquals("img_a", model.state.value.currentId)
        assertEquals("img_a", positions.values["http://nsfw.test:8767"])
    }

    @Test fun browseIdentityIsIsolatedByServerOrigin() = runTest {
        val positions = MemoryBrowsePositions().apply { values["http://other.test:8767"] = "img_z" }
        val model = bootstrapPaired(positions = positions)
        assertEquals("img_a", model.state.value.currentId)
        model.select("img_b")
        assertEquals("img_z", positions.values["http://other.test:8767"])
        assertEquals("img_b", positions.values["http://nsfw.test:8767"])
    }

    @Test fun lateBrowseRestoreCannotReopenDisconnectedConnection() = runTest {
        val gate = CompletableDeferred<String?>()
        val positions = object : com.mediareview.app.feature.v2.nsfw.data.NsfwBrowsePositionStore {
            override suspend fun loadPosition(origin: String): String? = gate.await()
            override suspend fun savePosition(origin: String, imageId: String) = Unit
        }
        val model = bootstrapPaired(positions = positions)
        model.clearPairing()
        gate.complete("img_b")
        assertEquals(NsfwPhase.UNPAIRED, model.state.value.phase)
        assertNull(model.state.value.currentId)
    }

    @Test fun missingSavedBrowseImageShowsExplicitFallback() = runTest {
        val positions = MemoryBrowsePositions().apply { values["http://nsfw.test:8767"] = "img_missing" }
        val model = bootstrapPaired(positions = positions)
        assertEquals("img_a", model.state.value.currentId)
        assertFalse(model.state.value.restoringBrowsePosition)
        assertTrue(model.state.value.browseRecoveryMessage.orEmpty().contains("不在当前图库"))
    }

    @Test fun rePairingSameOriginRestoresSavedImageAndGalleryAnchor() = runTest {
        val origin = "http://nsfw.test:8767"
        val expectedAnchor = com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryAnchor("img_a", 120)
        val positions = MemoryBrowsePositions().apply { values[origin] = "img_b"; anchors[origin] = expectedAnchor }
        val model = vm(positions = positions)
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_b"))) }
        gateway.pairResult = NsfwPairResult.Paired(origin, "device")
        model.pair(origin, "123456")
        assertEquals("img_b", model.state.value.currentId)
        assertEquals(expectedAnchor, model.state.value.galleryAnchor)
    }

    @Test fun failedGalleryReadCannotBeOverwrittenByInitialLayout() = runTest {
        val origin = "http://nsfw.test:8767"
        val expectedAnchor = com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryAnchor("img_b", 120)
        val positions = MemoryBrowsePositions().apply {
            anchors[origin] = expectedAnchor; failGalleryLoad = true; values[origin] = "img_b"
        }
        val model = bootstrapPaired(positions = positions)
        assertEquals("img_b", model.state.value.currentId)
        assertTrue(model.state.value.browseRecoveryMessage.orEmpty().contains("图库位置读取失败"))
        model.select("img_a")
        assertTrue(model.state.value.browseRecoveryMessage.orEmpty().contains("图库位置读取失败"))
        model.updateGalleryAnchor(com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryAnchor("img_a", 0))
        assertEquals(expectedAnchor, positions.anchors[origin])
        val deliberateAnchor = com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryAnchor("img_a", 40)
        model.updateGalleryAnchor(deliberateAnchor, userInitiated = true)
        assertEquals(deliberateAnchor, positions.anchors[origin])
        assertNull(model.state.value.browseRecoveryMessage)
    }

    @Test
    fun returningToForegroundSynchronizesImmediately() = runTest {
        val vm = bootstrapPaired()
        val before = data.feedCalls
        try {
            vm.setScreenVisible(true)
            assertEquals(before + 1, data.feedCalls)
            vm.setScreenVisible(false)
            vm.setScreenVisible(true)
            assertEquals(before + 2, data.feedCalls)
        } finally {
            vm.setScreenVisible(false)
        }
    }

    @Test
    fun disconnectWaitsForPendingDecision() = runTest {
        val vm = bootstrapPaired()
        val gate = CompletableDeferred<NsfwDecisionResult>()
        val receipt = NsfwDecisionResult("img_a", "KEEP", false, "2026-10-28T12:06:00Z", "2")
        data.decisionResponder = { _, _, _ -> gate.await() }
        try {
            vm.decision("KEEP")
            assertTrue(vm.state.value.busy)
            vm.clearPairing()
            assertEquals("提交未结束不能解除配对", NsfwPhase.READY, vm.state.value.phase)
            assertEquals(0, gateway.clearCalls)
            gate.complete(receipt)
            vm.clearPairing()
            assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
            assertEquals(1, gateway.clearCalls)
        } finally { gate.complete(receipt); vm.setScreenVisible(false) }
    }

    @Test
    fun syncReadAllowsDecisionAndStalePageCannotUndoIt() = runTest {
        val vm = bootstrapPaired()
        val gate = CompletableDeferred<com.mediareview.app.feature.v2.nsfw.data.NsfwFeedPage>()
        val stale = nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_c")), nextCursor = "new-cursor")
        data.feedResponder = { gate.await() }
        try {
            val calls = data.feedCalls
            vm.refresh(); vm.refresh()
            assertEquals(calls + 1, data.feedCalls)
            assertFalse("后台读取不应阻止审核", vm.state.value.busy)
            vm.decision("KEEP")
            assertEquals(1, data.decisionCalls)
            gate.complete(stale)
            assertEquals("KEEP", vm.state.value.items.first { it.imageId == "img_a" }.status)
            assertEquals("img_b", vm.state.value.currentId)
            assertEquals("已保留", vm.state.value.message)
        } finally { gate.complete(stale); vm.setScreenVisible(false) }
    }

    @Test
    fun syncReadFailureDoesNotUnlockPendingWrite() = runTest {
        val vm = bootstrapPaired()
        val read = CompletableDeferred<com.mediareview.app.feature.v2.nsfw.data.NsfwFeedPage>()
        val write = CompletableDeferred<NsfwDecisionResult>()
        val result = NsfwDecisionResult("img_a", "KEEP", false, "2026-10-28T12:06:00Z", "2")
        data.feedResponder = { read.await() }
        data.decisionResponder = { _, _, _ -> write.await() }
        try {
            vm.refresh(); vm.decision("KEEP")
            assertEquals(1, data.decisionCalls)
            assertTrue(vm.state.value.busy)
            read.completeExceptionally(java.io.IOException("read failed"))
            assertTrue("读取失败不能解除写入互斥", vm.state.value.busy)
            vm.decision("REJECT"); assertEquals(1, data.decisionCalls)
            write.complete(result)
            assertFalse(vm.state.value.busy)
        } finally { read.complete(nsfwPage(emptyList())); write.complete(result); vm.setScreenVisible(false) }
    }

    @Test
    fun mismatchedSnapshotDoesNotOfferUnsafeUndo() = runTest {
        val vm = bootstrapPaired()
        vm.decision("KEEP")
        assertNull(vm.state.value.undoDecision)
    }

    @Test
    fun transientUndoFailurePreservesReceiptWithoutAutomaticRetry() = runTest {
        val vm = bootstrapPaired(details = nsfwDetails("img_a", updatedAt = "2026-10-28T12:00:01Z"))
        vm.decision("REJECT")
        data.decisionResponder = { _, _, _ -> throw java.io.IOException("offline") }
        vm.undoLastDecision()
        assertEquals(2, data.decisionCalls)
        assertNotNull(vm.state.value.undoDecision)
        assertFalse(vm.state.value.busy)
        assertEquals("REJECT", vm.state.value.items.first { it.imageId == "img_a" }.status)
    }

    @Test
    fun undoRestoresPreviousStatusAndIsConsumed() = runTest {
        val vm = bootstrapPaired(details = nsfwDetails("img_a", updatedAt = "2026-10-28T12:00:01Z"))
        vm.decision("KEEP")
        assertNotNull(vm.state.value.undoDecision)
        data.decisionResponder = { id, action, favorite ->
            NsfwDecisionResult(id, if (action == "RESET") "NEW" else action ?: "NEW", favorite ?: false, "2026-10-30T10:01:00Z", "3")
        }
        vm.undoLastDecision()
        assertEquals("RESET", data.lastDecisionAction)
        assertEquals("2026-10-28T12:06:00Z", data.lastDecisionExpectedUpdatedAt)
        assertEquals("NEW", vm.state.value.items.first { it.imageId == "img_a" }.status)
        assertNull(vm.state.value.undoDecision)
        val calls = data.decisionCalls
        vm.undoLastDecision()
        assertEquals(calls, data.decisionCalls)
    }

    @Test
    fun undoConflictNeverOverwritesOrRetries() = runTest {
        val vm = bootstrapPaired(details = nsfwDetails("img_a", updatedAt = "2026-10-28T12:00:01Z"))
        vm.decision("KEEP")
        data.decisionResponder = { _, _, _ -> throw NsfwApiException(NsfwErrorCodes.REVIEW_CONFLICT, "冲突", 409) }
        vm.undoLastDecision()
        assertEquals(2, data.decisionCalls)
        assertNotNull(vm.state.value.conflictMessage)
        assertEquals("KEEP", vm.state.value.items.first { it.imageId == "img_a" }.status)
        assertNull(vm.state.value.undoDecision)
    }

    @Test
    fun viewingLatestConsumesBannerWithoutAutomaticJump() = runTest {
        val vm = bootstrapPaired()
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_c"))) }
        vm.refresh()
        assertEquals("img_a", vm.state.value.currentId)
        assertEquals(1, vm.state.value.newImageCount)
        vm.viewLatestImages()
        assertEquals("img_c", vm.state.value.currentId)
        assertEquals(0, vm.state.value.newImageCount)
    }

    @Test
    fun removedNewImageDoesNotLeavePhantomBanner() = runTest {
        val vm = bootstrapPaired()
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_c"))) }
        vm.refresh()
        assertEquals(1, vm.state.value.newImageCount)
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_c", revision = "2").copy(event = "REMOVE"))) }
        vm.refresh()
        assertEquals(0, vm.state.value.newImageCount)
        assertEquals("img_a", vm.state.value.currentId)
    }

    @Test
    fun newImageRemovedWithinSamePageIsNotCounted() = runTest {
        val vm = bootstrapPaired()
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_c"),
            nsfwItem("img_c", revision = "2").copy(event = "REMOVE"))) }
        vm.refresh()
        assertEquals(0, vm.state.value.newImageCount)
        assertFalse(vm.state.value.items.any { it.imageId == "img_c" })
    }

    @Test
    fun failedLaterPageKeepsAlreadyReceivedNewImageCount() = runTest {
        val vm = bootstrapPaired()
        var calls = 0
        data.feedResponder = {
            if (++calls == 1) nsfwPage(listOf(nsfwItem("img_c")), nextCursor = "increment-1", hasMore = true)
            else throw NsfwApiException("NETWORK_ERROR", "连接中断", 0)
        }
        vm.refresh()
        assertTrue(vm.state.value.items.any { it.imageId == "img_c" })
        assertEquals(1, vm.state.value.newImageCount)
        assertEquals("img_a", vm.state.value.currentId)
    }

    @Test
    fun failedLaterPageStillReconcilesRemovedCurrentImage() = runTest {
        val vm = bootstrapPaired()
        var calls = 0
        data.feedResponder = {
            if (++calls == 1) nsfwPage(listOf(nsfwItem("img_a", revision = "2").copy(event = "REMOVE")),
                nextCursor = "removed-current", hasMore = true)
            else throw NsfwApiException("NETWORK_ERROR", "连接中断", 0)
        }
        vm.refresh()
        assertEquals(listOf("img_b"), vm.state.value.items.map { it.imageId })
        assertEquals("img_b", vm.state.value.currentId)
        assertEquals("img_b", vm.state.value.detail?.imageId)
    }

    @Test
    fun confirmedDecisionAdvancesOnlyAfterReceipt() = runTest {
        val vm = bootstrapPaired()
        val receipt = CompletableDeferred<NsfwDecisionResult>()
        data.decisionResponder = { _, _, _ -> receipt.await() }
        vm.decision("KEEP")
        assertEquals("img_a", vm.state.value.currentId)
        receipt.complete(NsfwDecisionResult("img_a", "KEEP", false, "2026-10-30T10:00:00Z", "3"))
        assertEquals("img_b", vm.state.value.currentId)
    }

    @Test
    fun failedDecisionKeepsCurrentImage() = runTest {
        val vm = bootstrapPaired()
        data.decisionResponder = { _, _, _ -> throw java.io.IOException("offline") }
        vm.decision("REJECT")
        assertEquals("img_a", vm.state.value.currentId)
        assertEquals("NEW", vm.state.value.currentItem?.status)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun lateDecisionDoesNotStealUserSelection() = runTest {
        val vm = bootstrapPaired(page = nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_b"), nsfwItem("img_c"))))
        val receipt = CompletableDeferred<NsfwDecisionResult>()
        data.decisionResponder = { _, _, _ -> receipt.await() }
        vm.decision("KEEP")
        vm.select("img_c")
        receipt.complete(NsfwDecisionResult("img_a", "KEEP", false, "2026-10-30T10:00:00Z", "3"))
        assertEquals("img_c", vm.state.value.currentId)
    }

    @Test
    fun `未配对时进入配对页`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot()
        val vm = vm()
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertEquals(0, data.feedCalls)
    }

    @Test
    fun `已配对时加载feed并选中第一张`() = runTest {
        val vm = bootstrapPaired()
        val state = vm.state.value
        assertEquals(NsfwPhase.READY, state.phase)
        assertEquals(listOf("img_a", "img_b"), state.items.map { it.imageId })
        assertEquals("img_a", state.currentId)
        assertEquals("img_a", state.detail?.imageId)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `配对成功后加载数据且地址写入状态`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot()
        gateway.pairResult = NsfwPairResult.Paired("http://nsfw.test:8767", "dev_1")
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_a"))) }
        data.detailsResponder = { id -> nsfwDetails(id) }
        val vm = vm()
        vm.pair("nsfw.test:8767", "123456")
        assertEquals(NsfwPhase.READY, vm.state.value.phase)
        assertEquals(1, gateway.pairCalls)
        assertEquals("http://nsfw.test:8767", vm.state.value.baseUrl)
        assertEquals("img_a", vm.state.value.currentId)
    }

    @Test
    fun `能力不足时停留在配对页并保留缺失项`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot()
        gateway.pairResult = NsfwPairResult.Blocked(
            product = "nsfw-studio",
            apiContract = 1,
            missingCapabilities = listOf("mobile_feed_v2"),
            message = "当前 NSFW Bridge 不支持生图审核 v2",
        )
        val vm = vm()
        vm.pair("nsfw.test:8767", "123456")
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertEquals(listOf("mobile_feed_v2"), vm.state.value.missingCapabilities)
        assertTrue(vm.state.value.message!!.contains("不支持"))
    }

    @Test
    fun `多页增量自动追平且保持当前图片`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { cursor ->
            if (cursor == null) {
                nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_b")), nextCursor = "c2", hasMore = true)
            } else {
                nsfwPage(listOf(nsfwItem("img_b", revision = "2", status = "KEEP"), nsfwItem("img_c")), hasMore = false)
            }
        }
        data.detailsResponder = { id -> nsfwDetails(id) }
        val vm = vm()
        assertEquals(listOf("img_a", "img_b", "img_c"), vm.state.value.items.map { it.imageId })
        assertEquals("img_a", vm.state.value.currentId)
        assertEquals("KEEP", vm.state.value.items.first { it.imageId == "img_b" }.status)
        assertEquals(2, data.feedCalls)
    }

    @Test
    fun `刷新新图只追加到队尾且当前图片不变`() = runTest {
        val vm = bootstrapPaired()
        val beforeIndex = vm.state.value.currentIndex

        data.feedResponder = {
            nsfwPage(listOf(nsfwItem("img_a", revision = "2", status = "REJECT"), nsfwItem("img_c")))
        }
        vm.refresh()

        assertEquals(listOf("img_a", "img_b", "img_c"), vm.state.value.items.map { it.imageId })
        assertEquals(beforeIndex, vm.state.value.currentIndex)
        assertEquals("img_a", vm.state.value.currentId)
        assertEquals("REJECT", vm.state.value.items.first { it.imageId == "img_a" }.status)
        assertTrue(vm.state.value.newImageCount >= 1)
    }

    @Test
    fun `游标过期后从头同步且不打断当前浏览`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        var calls = 0
        data.feedResponder = {
            calls += 1
            if (calls == 1) {
                throw NsfwApiException(NsfwErrorCodes.CURSOR_EXPIRED, "游标已过期", 410)
            }
            nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_b")), nextCursor = "c_after", hasMore = false)
        }
        data.detailsResponder = { id -> nsfwDetails(id) }
        val vm = vm()
        assertFalse(vm.state.value.cursorExpired)
        assertEquals(listOf("img_a", "img_b"), vm.state.value.items.map { it.imageId })
        assertEquals("img_a", vm.state.value.currentId)
        assertEquals(2, data.feedCalls)
        assertTrue(vm.state.value.message!!.contains("从头重新同步"))
    }

    @Test
    fun `审核409只刷新权威状态不自动重提`() = runTest {
        data.decisionResponder = { _, _, _ ->
            throw NsfwApiException(NsfwErrorCodes.REVIEW_CONFLICT, "冲突", 409)
        }
        val vm = bootstrapPaired()
        val detailCallsBefore = data.detailCalls

        vm.decision("KEEP")

        assertEquals(1, data.decisionCalls)
        assertNotNull(vm.state.value.conflictMessage)
        assertEquals(detailCallsBefore + 1, data.detailCalls)
        assertEquals("NEW", vm.state.value.items.first { it.imageId == "img_a" }.status)
    }

    @Test
    fun `审核成功后原位更新当前图片`() = runTest {
        val vm = bootstrapPaired()
        vm.decision("KEEP")
        val item = vm.state.value.items.first { it.imageId == "img_a" }
        assertEquals("KEEP", item.status)
        assertEquals("2", item.revision)
    }

    @Test
    fun `未关联图片不允许打开评分面板`() = runTest {
        val vm = bootstrapPaired(details = nsfwDetails("img_a", promptId = null, versionId = null, linkState = "UNLINKED"))
        vm.openPromptPanel()
        assertNull(vm.state.value.panel)
        assertTrue(vm.state.value.message!!.contains("没有可靠的提示词关联"))
    }

    @Test
    fun `版本未知时必须显式选择整体评分才能提交`() = runTest {
        val vm = bootstrapPaired(
            details = nsfwDetails("img_a", promptId = "prm_1", versionId = null, linkState = "UNRESOLVED"),
        )
        data.summaryResponder = { id, version -> NsfwPromptSummary(promptId = id, promptVersionId = version) }
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }

        vm.openPromptPanel()
        val panel = vm.state.value.panel!!
        assertNull(panel.versionId)
        assertFalse(panel.scoringEnabled)

        vm.submitEvaluation(4, emptyMap(), "")
        assertEquals(0, data.createEvaluationCalls)
        assertTrue(vm.state.value.panel!!.message!!.contains("请先选择评分范围"))

        vm.confirmWholePromptScope()
        assertTrue(vm.state.value.panel!!.scoringEnabled)
        vm.submitEvaluation(4, mapOf("subject" to 4), "ok")
        assertEquals(1, data.createEvaluationCalls)
        assertNull(data.lastEvaluationRequest!!.promptVersionId)
        assertEquals(mapOf("subject" to 4), data.lastEvaluationRequest!!.dimensions)
        assertNotNull(vm.state.value.panel!!.submitted)
    }

    @Test
    fun `精确版本评分使用版本ID且只传填写维度`() = runTest {
        val vm = bootstrapPaired()
        data.summaryResponder = { id, version -> NsfwPromptSummary(promptId = id, promptVersionId = version) }
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        vm.openPromptPanel()
        assertEquals("prmv_1", vm.state.value.panel!!.versionId)
        assertTrue(vm.state.value.panel!!.scoringEnabled)

        vm.submitEvaluation(5, mapOf("lighting" to 3), "备注")

        assertEquals("prmv_1", data.lastEvaluationRequest!!.promptVersionId)
        assertEquals(mapOf("lighting" to 3), data.lastEvaluationRequest!!.dimensions)
        assertEquals("备注", data.lastEvaluationRequest!!.notes)
        assertNotNull(vm.state.value.panel!!.submitted)
    }

    @Test
    fun `评分超时先用同一请求号查回执不重复提交`() = runTest {
        val vm = bootstrapPaired()
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        data.createEvaluationResponder = { _, _ -> throw NsfwApiException("NETWORK_ERROR", "超时") }
        data.receiptResponder = { requestId ->
            NsfwEvaluationReceipt(
                found = true,
                evaluation = com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationResult(
                    evaluationId = "pev_9",
                    promptId = "prm_1",
                    promptVersionId = "prmv_1",
                    overallScore = 4,
                    clientRequestId = requestId,
                ),
            )
        }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")

        assertEquals(1, data.createEvaluationCalls)
        assertEquals(1, data.receiptCalls)
        assertNotNull(vm.state.value.panel!!.submitted)
    }

    @Test
    fun `评分结果未知时标记未确认并复用同一请求号重试`() = runTest {
        val vm = bootstrapPaired()
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        var attempt = 0
        data.createEvaluationResponder = { id, request ->
            attempt += 1
            if (attempt == 1) throw NsfwApiException("NETWORK_ERROR", "超时")
            com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationResult(
                evaluationId = "pev_1", promptId = id, promptVersionId = request.promptVersionId,
                overallScore = request.overallScore, clientRequestId = request.clientRequestId,
            )
        }
        data.receiptResponder = { NsfwEvaluationReceipt(found = false) }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")

        val firstId = data.lastEvaluationRequest!!.clientRequestId
        assertTrue(vm.state.value.panel!!.unconfirmed)
        assertNull(vm.state.value.panel!!.submitted)

        vm.submitEvaluation(4, emptyMap(), "")
        assertEquals(firstId, data.lastEvaluationRequest!!.clientRequestId)
        assertEquals(2, data.createEvaluationCalls)
        assertNotNull(vm.state.value.panel!!.submitted)
    }

    @Test
    fun `收藏只更新favorite且不改变NEW_REJECT_KEEP状态`() = runTest {
        listOf("NEW", "KEEP", "REJECT").forEach { originalStatus ->
            data = FakeNsfwDataSource()
            gateway = FakePairingGateway()
            data.feedResponder = { nsfwPage(listOf(nsfwItem("img_a", status = originalStatus))) }
            data.detailsResponder = { id -> nsfwDetails(id) }
            data.decisionResponder = { id, _, favorite ->
                NsfwDecisionResult(
                    imageId = id,
                    status = originalStatus,
                    isFavorite = favorite ?: false,
                    updatedAt = "2026-10-28T12:06:00Z",
                    revision = "2",
                )
            }
            val vm = bootstrapPaired(
                page = nsfwPage(listOf(nsfwItem("img_a", status = originalStatus))),
            )
            vm.toggleFavorite()

            assertNull(data.lastDecisionActionNullable)
            assertEquals(true, vm.state.value.items.first { it.imageId == "img_a" }.isFavorite)
            assertEquals(originalStatus, vm.state.value.items.first { it.imageId == "img_a" }.status)
        }
    }

    @Test
    fun `审核成功后的旧feed页不覆盖新状态`() = runTest {
        val vm = bootstrapPaired(page = nsfwPage(listOf(nsfwItem("img_a", revision = "1"))))
        data.decisionResponder = { id, action, favorite ->
            NsfwDecisionResult(id, action ?: "NEW", favorite ?: false, "2026-10-28T12:06:00Z", "3")
        }
        vm.decision("KEEP")
        assertEquals("KEEP", vm.state.value.items.first().status)

        data.feedResponder = {
            nsfwPage(listOf(nsfwItem("img_a", revision = "2", status = "NEW")))
        }
        vm.refresh()

        assertEquals("KEEP", vm.state.value.items.first().status)
        assertEquals("3", vm.state.value.items.first().revision)
    }

    @Test
    fun `详情401只清NSFW并回到配对页`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_a"))) }
        data.detailsResponder = { throw NsfwApiException(NsfwErrorCodes.AUTH_REVOKED, "吊销", 401) }
        val vm = vm()
        assertEquals(1, gateway.authRejectedCalls)
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertTrue(vm.state.value.message!!.contains("MediaReview 服务器不受影响"))
    }

    @Test
    fun `启动恢复发现旧Bridge时不进入审核页但保留已保存token`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(
            baseUrl = "http://nsfw.test:8767",
            paired = true,
            compatible = false,
            missingCapabilities = listOf("mobile_feed_v2"),
            message = "已保存的配对仍然有效，但当前 Bridge 不支持生图审核 v2",
        )
        val vm = vm()
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertEquals(listOf("mobile_feed_v2"), vm.state.value.missingCapabilities)
        assertEquals(0, data.feedCalls)
        assertEquals(0, gateway.clearCalls)
    }

    @Test
    fun `完成评分后清除该请求的持久化记录`() = runTest {
        val saved = SavedStateHandle()
        val vm = bootstrapPaired(savedState = saved)
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")
        assertTrue(vm.pendingRequestIds().isEmpty())
        assertNotNull(vm.state.value.panel!!.submitted)
    }

    @Test
    fun `断开配对清空页面状态`() = runTest {
        val vm = bootstrapPaired()
        vm.clearPairing()
        assertEquals(1, gateway.clearCalls)
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `页面可见时周期增量同步离开页面立即停止`() = runTest {
        val vm = bootstrapPaired()
        vm.pollIntervalMs = 1_000
        val before = data.feedCalls

        vm.setScreenVisible(true)
        dispatcher.scheduler.advanceTimeBy(1_100)
        dispatcher.scheduler.runCurrent()
        assertTrue(data.feedCalls > before)

        vm.setScreenVisible(false)
        val afterStop = data.feedCalls
        dispatcher.scheduler.advanceTimeBy(10_000)
        dispatcher.scheduler.runCurrent()
        assertEquals(afterStop, data.feedCalls)
    }

    @Test
    fun `持续410只重置一次后明确报错不无限循环`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { throw NsfwApiException(NsfwErrorCodes.CURSOR_EXPIRED, "过期", 410) }
        val vm = vm()
        assertEquals(2, data.feedCalls)
        assertTrue(vm.state.value.message!!.contains("从头同步失败"))
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `游标不推进时停止本轮同步并报错`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { cursor ->
            nsfwPage(listOf(nsfwItem("img_a")), nextCursor = cursor ?: "same", hasMore = true)
        }
        val vm = vm()
        assertEquals(2, data.feedCalls)
        assertTrue(vm.state.value.message!!.contains("游标未推进"))
        assertFalse(vm.state.value.hasMore)
    }

    @Test
    fun `超过250张跨页追平且尾态hasMore准确`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { cursor ->
            val pageIndex = cursor?.removePrefix("c")?.toIntOrNull() ?: 0
            val start = pageIndex * 50
            val items = (start until start + 50).map { nsfwItem("img_$it") }
            val next = "c${pageIndex + 1}"
            nsfwPage(items, nextCursor = next, hasMore = pageIndex < 5)
        }
        data.detailsResponder = { id -> nsfwDetails(id) }
        val vm = vm()
        assertEquals(300, vm.state.value.items.size)
        assertEquals(6, data.feedCalls)
        assertFalse(vm.state.value.hasMore)
    }

    @Test
    fun `REMOVE当前图片后自动选择下一条`() = runTest {
        val vm = bootstrapPaired(page = nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_b"))))
        data.feedResponder = {
            nsfwPage(listOf(nsfwItem("img_a", revision = "2").copy(event = "REMOVE")))
        }
        vm.refresh()
        assertEquals(listOf("img_b"), vm.state.value.items.map { it.imageId })
        assertEquals("img_b", vm.state.value.currentId)
        assertEquals("img_b", vm.state.value.detail?.imageId)
    }

    @Test
    fun `未确认评分修改内容必须换新请求号且旧记录保留`() = runTest {
        val vm = bootstrapPaired()
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        data.createEvaluationResponder = { _, _ -> throw NsfwApiException("NETWORK_ERROR", "超时") }
        data.receiptResponder = { NsfwEvaluationReceipt(found = false) }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")
        val firstId = data.lastEvaluationRequest!!.clientRequestId
        assertTrue(vm.state.value.panel!!.unconfirmed)

        vm.submitEvaluation(2, emptyMap(), "")
        assertEquals(1, data.createEvaluationCalls)
        assertTrue(vm.state.value.panel!!.message!!.contains("新建评分请求"))

        vm.resetEvaluationDraft()
        val newId = vm.state.value.panel!!.requestId
        assertTrue(newId != firstId)
        assertTrue(vm.state.value.panel!!.message!!.contains("旧请求"))
        assertTrue(vm.pendingRequestIds().contains(firstId))

        vm.submitEvaluation(2, emptyMap(), "")
        assertEquals(2, data.createEvaluationCalls)
        assertEquals(newId, data.lastEvaluationRequest!!.clientRequestId)
        // 新请求成功只清除自己的记录，旧未确认请求仍保留可查回执
        assertTrue(vm.pendingRequestIds().contains(firstId))
    }

    @Test
    fun `进程重启后未确认评分只提示不自动重发`() = runTest {
        val pending = NsfwPendingEvaluation(
            serverOrigin = "http://nsfw.test:8767",
            promptId = "prm_1",
            versionId = "prmv_1",
            request = NsfwEvaluationRequest(
                promptVersionId = "prmv_1",
                overallScore = 4,
                dimensions = mapOf("subject" to 4),
                notes = "",
                clientRequestId = "req-pending",
            ),
        )
        val json = Json { ignoreUnknownKeys = true }
        val pendingJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(NsfwPendingEvaluation.serializer()),
            listOf(pending),
        )
        val saved = SavedStateHandle(mapOf("nsfw_pending_evaluations" to pendingJson))
        val vm = bootstrapPaired(savedState = saved)
        assertTrue(vm.state.value.evaluationRecoveryHint!!.contains("未确认"))
        assertTrue(vm.pendingRequestIds().contains("req-pending"))
        assertEquals(0, data.createEvaluationCalls)
        assertEquals(0, data.receiptCalls)
    }

    @Test
    fun `快速切图时旧详情回包不覆盖新图`() = runTest {
        val firstDetails = CompletableDeferred<com.mediareview.app.feature.v2.nsfw.data.NsfwImageDetails>()
        var detailCall = 0
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_b"))) }
        data.detailsResponder = { id ->
            detailCall += 1
            if (detailCall == 1) firstDetails.await() else nsfwDetails("img_b")
        }
        val vm = vm()
        assertEquals("img_a", vm.state.value.currentId)
        assertTrue(vm.state.value.detailLoading)

        vm.select("img_b")
        assertEquals("img_b", vm.state.value.detail?.imageId)
        firstDetails.complete(nsfwDetails("img_a"))
        assertEquals("img_b", vm.state.value.detail?.imageId)
        assertFalse(vm.state.value.detailLoading)
    }

    @Test
    fun `关闭评分面板后旧面板回包被丢弃`() = runTest {
        val summaryDeferred = CompletableDeferred<NsfwPromptSummary>()
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_a"))) }
        data.detailsResponder = { id -> nsfwDetails(id) }
        data.summaryResponder = { _, _ -> summaryDeferred.await() }
        val vm = vm()
        vm.openPromptPanel()
        assertNotNull(vm.state.value.panel)
        vm.closePromptPanel()
        summaryDeferred.complete(NsfwPromptSummary(promptId = "prm_1", promptVersionId = "prmv_1"))
        assertNull(vm.state.value.panel)
    }

    @Test
    fun `初次历史加载不计入新到图片数后续增量才计数`() = runTest {
        val vm = bootstrapPaired(page = nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_b"))))
        assertEquals(0, vm.state.value.newImageCount)

        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_c"))) }
        vm.refresh()
        assertEquals(1, vm.state.value.newImageCount)
    }

    @Test
    fun historyAcrossBoundedSyncsDoesNotBecomeNewImages() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8877", paired = true)
        var calls = 0
        data.feedResponder = {
            calls += 1
            nsfwPage(listOf(nsfwItem("history_$calls")), nextCursor = "history-c$calls", hasMore = calls < 21)
        }
        val vm = vm()
        assertEquals(10, calls)
        vm.refresh()
        assertEquals(20, calls)
        assertEquals(0, vm.state.value.newImageCount)
        vm.loadMore()
        assertEquals(21, calls)
        assertEquals(0, vm.state.value.newImageCount)
        vm.refresh()
        assertEquals(1, vm.state.value.newImageCount)
    }

    @Test
    fun `hasMore为true但缺少nextCursor时停止并报错`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_a")), nextCursor = null, hasMore = true) }
        val vm = vm()
        assertEquals(1, data.feedCalls)
        assertFalse(vm.state.value.hasMore)
        assertNull(vm.state.value.nextCursor)
        assertTrue(vm.state.value.message!!.contains("next_cursor"))
    }

    @Test
    fun `加载更多遇到410只重置一次并从空游标完成`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        var calls = 0
        data.feedResponder = { cursor ->
            calls += 1
            if (calls <= 10) {
                nsfwPage(listOf(nsfwItem("img_$calls")), nextCursor = "c$calls", hasMore = true)
            } else if (calls == 11) {
                throw NsfwApiException(NsfwErrorCodes.CURSOR_EXPIRED, "过期", 410)
            } else {
                nsfwPage(emptyList(), nextCursor = null, hasMore = false)
            }
        }
        val vm = vm()
        assertEquals(10, data.feedCalls)
        assertTrue(vm.state.value.hasMore)

        vm.loadMore()

        assertEquals(12, data.feedCalls)
        assertFalse(vm.state.value.hasMore)
        assertFalse(vm.state.value.cursorExpired)
        // 410 恢复必须显示"从头重新同步"，且不沿用旧游标
        assertTrue(vm.state.value.message!!.contains("从头重新同步"))
    }

    @Test
    fun `加载更多游标不推进时停止并报错`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        var calls = 0
        data.feedResponder = { cursor ->
            calls += 1
            if (calls <= 10) {
                nsfwPage(listOf(nsfwItem("img_$calls")), nextCursor = "c$calls", hasMore = true)
            } else {
                nsfwPage(listOf(nsfwItem("img_extra")), nextCursor = cursor ?: "c10", hasMore = true)
            }
        }
        val vm = vm()
        vm.loadMore()
        assertFalse(vm.state.value.hasMore)
        assertTrue(vm.state.value.message!!.contains("游标未推进"))
        assertEquals(11, data.feedCalls)
    }

    @Test
    fun `REMOVE最后一张图片安全进入空状态`() = runTest {
        val vm = bootstrapPaired(page = nsfwPage(listOf(nsfwItem("only"))))
        data.feedResponder = {
            nsfwPage(listOf(nsfwItem("only", revision = "2").copy(event = "REMOVE")))
        }
        vm.refresh()
        assertTrue(vm.state.value.items.isEmpty())
        assertNull(vm.state.value.currentId)
        assertNull(vm.state.value.detail)
    }

    @Test
    fun `健康检查无法确认兼容性时不启动feed和轮询`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(
            baseUrl = "http://nsfw.test:8767",
            paired = true,
            compatible = null,
            message = "暂时无法校验 Bridge 版本，请检查连接",
        )
        val vm = vm()
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertEquals(0, data.feedCalls)

        vm.setScreenVisible(true)
        dispatcher.scheduler.advanceTimeBy(20_000)
        dispatcher.scheduler.runCurrent()
        assertEquals(0, data.feedCalls)
    }

    @Test
    fun `断开配对后延迟返回的旧feed不得恢复READY`() = runTest {
        val delayedFeed = CompletableDeferred<com.mediareview.app.feature.v2.nsfw.data.NsfwFeedPage>()
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { delayedFeed.await() }
        data.detailsResponder = { id -> nsfwDetails(id) }
        val vm = vm()
        assertTrue(vm.state.value.syncing)

        vm.clearPairing()
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        delayedFeed.complete(nsfwPage(listOf(nsfwItem("img_a"))))

        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `离开页面取消轮询不产生普通错误提示`() = runTest {
        val vm = bootstrapPaired(
            page = nsfwPage(listOf(nsfwItem("img_a")), nextCursor = "c_next", hasMore = false),
        )
        assertEquals(null, vm.state.value.message)
        val delayedFeed = CompletableDeferred<com.mediareview.app.feature.v2.nsfw.data.NsfwFeedPage>()
        data.feedResponder = { delayedFeed.await() }
        vm.pollIntervalMs = 1_000

        vm.setScreenVisible(true)
        dispatcher.scheduler.advanceTimeBy(1_100)
        dispatcher.scheduler.runCurrent()

        vm.setScreenVisible(false)
        dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `提示词A的未确认评分不能被提示词B覆盖`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = {
            nsfwPage(
                listOf(
                    nsfwItem("img_a", promptId = "prm_1", versionId = "prmv_1"),
                    nsfwItem("img_b", promptId = "prm_2", versionId = "prmv_2"),
                ),
            )
        }
        data.detailsResponder = { id ->
            if (id == "img_a") nsfwDetails("img_a", promptId = "prm_1", versionId = "prmv_1")
            else nsfwDetails("img_b", promptId = "prm_2", versionId = "prmv_2")
        }
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        data.receiptResponder = { NsfwEvaluationReceipt(found = false) }
        data.createEvaluationResponder = { id, request ->
            if (id == "prm_1") throw NsfwApiException("NETWORK_ERROR", "超时")
            com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationResult(
                evaluationId = "pev_b", promptId = id, promptVersionId = request.promptVersionId,
                overallScore = request.overallScore, clientRequestId = request.clientRequestId,
            )
        }
        val vm = vm()
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")
        val pendingA = vm.pendingRequestIds().single()
        vm.closePromptPanel()

        vm.select("img_b")
        vm.openPromptPanel()
        vm.submitEvaluation(5, emptyMap(), "")
        assertNotNull(vm.state.value.panel!!.submitted)
        assertTrue(vm.pendingRequestIds().contains(pendingA))
        assertEquals(1, vm.pendingRequestIds().size)
    }

    @Test
    fun `未确认请求达到上限时拒绝继续堆积`() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val pending = (1..5).map { index ->
            NsfwPendingEvaluation(
                serverOrigin = "http://nsfw.test:8767",
                promptId = "prm_other_$index",
                versionId = "prmv_other_$index",
                request = NsfwEvaluationRequest(
                    promptVersionId = "prmv_other_$index",
                    overallScore = 4,
                    clientRequestId = "req-other-$index",
                ),
            )
        }
        val saved = SavedStateHandle(
            mapOf(
                "nsfw_pending_evaluations" to json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(NsfwPendingEvaluation.serializer()),
                    pending,
                ),
            ),
        )
        val vm = bootstrapPaired(savedState = saved)
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        vm.openPromptPanel()
        vm.submitEvaluation(5, emptyMap(), "")
        assertEquals(0, data.createEvaluationCalls)
        assertTrue(vm.state.value.panel!!.message!!.contains("未确认"))
    }

    @Test
    fun `较旧审核回包不显示成功结论并刷新权威状态`() = runTest {
        val vm = bootstrapPaired(page = nsfwPage(listOf(nsfwItem("img_a", revision = "5"))))
        data.decisionResponder = { id, _, favorite ->
            NsfwDecisionResult(id, "KEEP", favorite ?: false, "2026-10-28T12:00:00Z", "2")
        }
        val before = data.detailCalls
        vm.decision("REJECT")
        assertNotNull(vm.state.value.conflictMessage)
        assertTrue(vm.state.value.message == null || !vm.state.value.message!!.contains("已淘汰"))
        assertEquals(before + 1, data.detailCalls)
        assertEquals("NEW", vm.state.value.items.first().status)
    }

    @Test
    fun `EXACT缺少版本和未知关联状态都拒绝评分`() = runTest {
        var vm = bootstrapPaired(
            details = nsfwDetails("img_a", promptId = "prm_1", versionId = null, linkState = "EXACT"),
        )
        vm.openPromptPanel()
        assertNull(vm.state.value.panel)
        assertTrue(vm.state.value.message!!.contains("缺少提示词或版本"))

        data = FakeNsfwDataSource()
        gateway = FakePairingGateway()
        vm = bootstrapPaired(
            details = nsfwDetails("img_a", promptId = "prm_1", versionId = null, linkState = "WEIRD"),
        )
        vm.openPromptPanel()
        assertNull(vm.state.value.panel)
        assertTrue(vm.state.value.message!!.contains("未知的提示词关联状态"))
    }

    @Test
    fun `空字符串nextCursor被拒绝且刷新从空游标恢复`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = {
            nsfwPage(listOf(nsfwItem("img_a")), nextCursor = "", hasMore = true)
        }
        data.detailsResponder = { id -> nsfwDetails(id) }
        val vm = vm()

        assertEquals(1, data.feedCalls)
        assertFalse(vm.state.value.hasMore)
        assertNull(vm.state.value.nextCursor)
        assertTrue(vm.state.value.message!!.contains("next_cursor"))

        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_b"))) }
        vm.refresh()

        assertNull(data.lastFeedCursor)
        assertTrue(vm.state.value.items.any { it.imageId == "img_b" })
    }

    @Test
    fun `410后多页历史重建全程不增加新图计数`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        var rebuilding = false
        data.feedResponder = { cursor ->
            val c = cursor
            when {
                c == "c10" -> {
                    rebuilding = true
                    throw NsfwApiException(NsfwErrorCodes.CURSOR_EXPIRED, "过期", 410)
                }
                rebuilding && c == null -> nsfwPage(listOf(nsfwItem("img_r1")), "rc1", true)
                rebuilding && c == "rc1" -> nsfwPage(listOf(nsfwItem("img_r2")), "rc2", true)
                rebuilding && c == "rc2" -> nsfwPage(listOf(nsfwItem("img_r3")), null, false)
                c == null -> nsfwPage(listOf(nsfwItem("img_h0")), "c1", true)
                else -> {
                    val index = c!!.removePrefix("c").toInt()
                    nsfwPage(listOf(nsfwItem("img_h$index")), "c${index + 1}", true)
                }
            }
        }
        data.detailsResponder = { id -> nsfwDetails(id) }
        val vm = vm()
        assertEquals(10, data.feedCalls)
        assertEquals(10, vm.state.value.items.size)

        vm.refresh()

        assertEquals(0, vm.state.value.newImageCount)
        assertEquals(13, vm.state.value.items.size)
        assertTrue(vm.state.value.message!!.contains("从头重新同步"))
    }

    @Test
    fun `游标停止后内部水位清零且刷新从空游标恢复`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        var calls = 0
        data.feedResponder = { cursor ->
            calls += 1
            if (calls == 1) {
                nsfwPage(listOf(nsfwItem("img_a")), "c1", true)
            } else {
                nsfwPage(listOf(nsfwItem("img_b")), cursor ?: "c1", true)
            }
        }
        data.detailsResponder = { id -> nsfwDetails(id) }
        val vm = vm()
        assertEquals(2, data.feedCalls)
        assertTrue(vm.state.value.message!!.contains("游标未推进"))

        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_c"))) }
        vm.refresh()

        assertNull(data.lastFeedCursor)
        assertTrue(vm.state.value.items.any { it.imageId == "img_c" })
    }

    @Test
    fun `关闭评分面板后成功回执仍清除该条未确认记录`() = runTest {
        val deferred = CompletableDeferred<com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationResult>()
        val vm = bootstrapPaired()
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        data.createEvaluationResponder = { _, _ -> deferred.await() }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")
        val requestId = data.lastEvaluationRequest!!.clientRequestId
        assertTrue(vm.pendingRequestIds().contains(requestId))

        vm.closePromptPanel()
        deferred.complete(
            com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationResult(
                evaluationId = "pev_1",
                promptId = "prm_1",
                promptVersionId = "prmv_1",
                overallScore = 4,
                clientRequestId = requestId,
            ),
        )

        assertTrue(vm.pendingRequestIds().isEmpty())
        assertNull(vm.state.value.evaluationRecoveryHint)
        assertNull(vm.state.value.panel)
    }

    @Test
    fun `同一提示词多条未确认记录可逐条切换核对`() = runTest {
        data.createEvaluationResponder = { _, _ -> throw NsfwApiException("NETWORK_ERROR", "超时") }
        data.receiptResponder = { NsfwEvaluationReceipt(found = false) }
        val vm = bootstrapPaired()
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")
        val firstId = data.lastEvaluationRequest!!.clientRequestId

        vm.resetEvaluationDraft()
        vm.submitEvaluation(3, emptyMap(), "")
        val secondId = data.lastEvaluationRequest!!.clientRequestId
        assertTrue(firstId != secondId)

        val panel = vm.state.value.panel!!
        assertEquals(2, panel.pendingRequests.size)
        assertEquals(secondId, panel.requestId)

        vm.selectPendingEvaluation(0)
        assertEquals(firstId, vm.state.value.panel!!.requestId)
        assertEquals(4, vm.state.value.panel!!.pendingRequest!!.overallScore)
        vm.selectPendingEvaluation(1)
        assertEquals(secondId, vm.state.value.panel!!.requestId)
        assertEquals(3, vm.state.value.panel!!.pendingRequest!!.overallScore)
    }

    @Test
    fun `成功一条评分后恢复提示按剩余记录重算`() = runTest {
        val journal = FakeNsfwEvaluationJournal(
            items = listOf(
                pendingEvaluation("req-a", promptId = "prm_1", versionId = "prmv_1"),
                pendingEvaluation("req-b", promptId = "prm_2", versionId = "prmv_2"),
            ),
        )
        data.receiptResponder = { NsfwEvaluationReceipt(found = false) }
        val vm = bootstrapPaired(journal = journal)
        assertTrue(vm.state.value.evaluationRecoveryHint!!.contains("2 条"))
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }

        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")

        assertEquals(listOf("req-b"), vm.pendingRequestIds())
        assertTrue(vm.state.value.evaluationRecoveryHint!!.contains("1 条"))
        assertNotNull(vm.state.value.panel!!.submitted)
    }

    @Test
    fun `评分响应身份不一致时保留未确认记录且不显示成功`() = runTest {
        val vm = bootstrapPaired()
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        data.createEvaluationResponder = { _, _ ->
            throw NsfwApiException(NsfwErrorCodes.RESPONSE_MISMATCH, "评分响应请求号不一致")
        }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")

        assertNull(vm.state.value.panel!!.submitted)
        assertTrue(vm.state.value.panel!!.unconfirmed)
        assertTrue(vm.state.value.panel!!.message!!.contains("不一致"))
        assertEquals(1, vm.pendingRequestIds().size)
        assertEquals(0, data.receiptCalls)
    }

    @Test
    fun `成功评分只清除当前服务器来源的未确认记录`() = runTest {
        val journal = FakeNsfwEvaluationJournal(
            items = listOf(
                pendingEvaluation("req-shared").copy(serverOrigin = "http://nsfw.test:8767"),
                pendingEvaluation("req-shared").copy(serverOrigin = "http://other.test:8767"),
            ),
        )
        data.receiptResponder = { NsfwEvaluationReceipt(found = false) }
        val vm = bootstrapPaired(journal = journal)
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")

        assertEquals(listOf("req-shared"), vm.pendingRequestIds())
        assertNotNull(vm.state.value.panel!!.submitted)
    }

    @Test
    fun `持久化失败时拒绝发送评分请求`() = runTest {
        val journal = FakeNsfwEvaluationJournal().apply { failSave = true }
        val vm = bootstrapPaired(journal = journal)
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")

        assertEquals(0, data.createEvaluationCalls)
        assertEquals(0, data.receiptCalls)
        assertTrue(vm.pendingRequestIds().isEmpty())
        assertTrue(vm.state.value.panel!!.message!!.contains("未发送"))
    }

    @Test
    fun `DataStore记录重启后可恢复未确认提示且不自动重发`() = runTest {
        val journal = FakeNsfwEvaluationJournal(
            items = listOf(pendingEvaluation("req-pending")),
        )
        val vm = bootstrapPaired(journal = journal)
        assertTrue(vm.state.value.evaluationRecoveryHint!!.contains("1 条"))
        assertTrue(vm.pendingRequestIds().contains("req-pending"))
        assertEquals(0, data.createEvaluationCalls)
        assertEquals(0, data.receiptCalls)
    }

    @Test
    fun `重启后重试先查回执确认已保存则不重发`() = runTest {
        val journal = FakeNsfwEvaluationJournal(
            items = listOf(pendingEvaluation("req-pending")),
        )
        data.receiptResponder = { requestId ->
            NsfwEvaluationReceipt(
                found = true,
                evaluation = com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationResult(
                    evaluationId = "pev_1",
                    promptId = "prm_1",
                    promptVersionId = "prmv_1",
                    overallScore = 4,
                    clientRequestId = requestId,
                ),
            )
        }
        val vm = bootstrapPaired(journal = journal)
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")

        assertEquals(1, data.receiptCalls)
        assertEquals(0, data.createEvaluationCalls)
        assertNotNull(vm.state.value.panel!!.submitted)
        assertTrue(vm.pendingRequestIds().isEmpty())
    }

    @Test
    fun `重启后重试回执缺失才使用同一请求号重发`() = runTest {
        val journal = FakeNsfwEvaluationJournal(
            items = listOf(pendingEvaluation("req-pending")),
        )
        data.receiptResponder = { NsfwEvaluationReceipt(found = false) }
        val vm = bootstrapPaired(journal = journal)
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")

        assertEquals(1, data.receiptCalls)
        assertEquals(1, data.createEvaluationCalls)
        assertEquals("req-pending", data.lastEvaluationRequest!!.clientRequestId)
        assertNotNull(vm.state.value.panel!!.submitted)
    }

    @Test
    fun `已有配对健康检查失败后可无需配对码重试`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(
            baseUrl = "http://nsfw.test:8767",
            paired = true,
            compatible = null,
            message = "暂时无法校验 Bridge 版本，请检查连接",
        )
        val vm = vm()
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertTrue(vm.state.value.hasSavedPairing)
        assertEquals(0, data.feedCalls)

        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        vm.retryConnectionCheck()

        assertEquals(NsfwPhase.READY, vm.state.value.phase)
        assertEquals(1, data.feedCalls)
        assertEquals(0, gateway.pairCalls)
    }

    @Test
    fun `评分提交禁止解除配对且凭据失效后旧评分不覆盖新状态`() = runTest {
        val deferred = CompletableDeferred<com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationResult>()
        val vm = bootstrapPaired()
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        data.createEvaluationResponder = { _, _ -> deferred.await() }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")
        val requestId = data.lastEvaluationRequest!!.clientRequestId

        vm.clearPairing()
        assertEquals(NsfwPhase.READY, vm.state.value.phase)
        assertEquals(0, gateway.clearCalls)
        data.feedResponder = { throw NsfwApiException("AUTH_REQUIRED", "凭据已失效", 401) }
        vm.refresh()
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        val authState = vm.state.value
        deferred.complete(
            com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationResult(
                evaluationId = "pev_1",
                promptId = "prm_1",
                promptVersionId = "prmv_1",
                overallScore = 4,
                clientRequestId = requestId,
            ),
        )

        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertEquals(authState, vm.state.value)
        assertTrue(vm.pendingRequestIds().isEmpty())
    }

    private fun pendingEvaluation(
        requestId: String,
        promptId: String = "prm_1",
        versionId: String = "prmv_1",
        score: Int = 4,
    ) = NsfwPendingEvaluation(
        serverOrigin = "http://nsfw.test:8767",
        promptId = promptId,
        versionId = versionId,
        request = NsfwEvaluationRequest(
            promptVersionId = versionId,
            overallScore = score,
            notes = "",
            clientRequestId = requestId,
        ),
    )
}
