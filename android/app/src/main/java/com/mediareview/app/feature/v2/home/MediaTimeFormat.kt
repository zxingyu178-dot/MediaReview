package com.mediareview.app.feature.v2.home

/** 时长格式化：mm:ss，首页与播放器共用。 */
fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}
