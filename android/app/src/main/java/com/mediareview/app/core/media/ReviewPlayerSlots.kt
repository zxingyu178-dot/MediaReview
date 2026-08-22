package com.mediareview.app.core.media

/**
 * 批阅双播放器槽位状态机(纯逻辑,可 JVM 单测)。
 *
 * 跟踪两个槽位(A=player / B=preload)各自的已就绪索引与当前活动槽,
 * 并回答"切到某索引时应选哪个槽、是否已就绪"。任何 stop 都会同步清空
 * 对应槽位的 ready,避免 P1 stale-ready(快速滑到被停止的预加载项时重新 prepare)。
 */
class ReviewPlayerSlots {

    /** 槽 A 已就绪的队列索引;-1 表示未就绪。 */
    var readyA: Int = -1
    /** 槽 B 已就绪的队列索引;-1 表示未就绪。 */
    var readyB: Int = -1
    /** 当前播放/发声的槽是否为 B(preload)。 */
    var activeIsB: Boolean = false

    val activeReady: Int get() = if (activeIsB) readyB else readyA

    /**
     * 为索引 [index] 选择播放目标槽。
     * @return (targetIsB, alreadyReady):目标槽是否已就绪该索引。
     */
    fun decideTarget(index: Int): Pair<Boolean, Boolean> {
        val targetIsB = readyB == index
        val alreadyReady = if (targetIsB) true else readyA == index
        return targetIsB to alreadyReady
    }

    /** 标记某槽已就绪到指定索引(索引可为 -1 表示清空)。 */
    fun markReady(slotB: Boolean, index: Int) {
        if (slotB) readyB = index else readyA = index
    }

    /** 标记活动槽。 */
    fun markActive(slotB: Boolean) {
        activeIsB = slotB
    }

    /** 停止非活动槽并清空其 ready(防 stale-ready)。 */
    fun stopInactive() {
        if (activeIsB) readyA = -1 else readyB = -1
    }

    /** 停止指定槽并清空其 ready。 */
    fun stopSlot(slotB: Boolean) {
        markReady(slotB, -1)
    }

    fun reset() {
        readyA = -1
        readyB = -1
        activeIsB = false
    }
}
