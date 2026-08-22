package com.mediareview.app.core.media

/**
 * latest-wins 调度令牌(纯逻辑,可 JVM 单测)。
 *
 * 批阅页面快速连续滑动时,每次 [onSettled] 会取消旧任务并调用 [next] 领取新令牌;
 * 网络/准备阶段返回后必须用 [isValid] 校验,只有最新令牌对应的切换才真正执行,
 * 彻底杜绝"旧协程晚于新协程完成导致播放串位"的异步乱序。
 *
 * 令牌计数器**永久单调递增,永不复用**:
 * - [next] 每次 +1;
 * - [reset] 把计数器前移一个大步长,使当前在途令牌立即失效,但历史令牌永不再次出现。
 */
class LatestWinsScheduler {
    private var latest = -1L

    /** 领取新令牌(全局单调递增)。 */
    fun next(): Long {
        latest += 1
        return latest
    }

    /** 该令牌是否仍是最新的(旧令牌返回 false)。 */
    fun isValid(token: Long): Boolean = token == latest

    /**
     * 使当前在途令牌立即失效(如会话切换时)。
     * 计数器只前移不倒退,因此任何历史令牌都不会被复用。
     */
    fun reset() {
        latest += 1L shl 40
    }
}
