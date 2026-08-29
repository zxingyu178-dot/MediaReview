package com.mediareview.app.core.media

/** ReviewViewModel 的最小播放控制边界，便于确定性验证生命周期竞态。 */
interface ReviewPlaybackController {
    fun settle(index: Int, current: ReviewPlayable)
    fun prepareNext(index: Int, item: ReviewPlayable?)
    fun snapshotFor(mediaId: String): ProgressSnapshot?
    fun onSwipeStarted()
    fun deactivateReview()
}
