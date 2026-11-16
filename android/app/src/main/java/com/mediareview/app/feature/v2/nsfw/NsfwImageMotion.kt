package com.mediareview.app.feature.v2.nsfw

/** 身份保持时不切图；审核移除当前项后索引不变，仍按向下一张处理。 */
internal fun imageMotionDirection(previousId: String?, currentId: String?, previousIndex: Int, currentIndex: Int): Int = when {
    previousId == null || currentId == null || previousId == currentId -> 0
    currentIndex < previousIndex -> -1
    else -> 1
}
