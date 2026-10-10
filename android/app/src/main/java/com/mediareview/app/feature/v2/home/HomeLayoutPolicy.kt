package com.mediareview.app.feature.v2.home

enum class HomeDisplayMode(val columns: Int) {
    ONE_COLUMN(1), TWO_COLUMN(2);

    companion object {
        fun fromStored(raw: String?): HomeDisplayMode = entries.firstOrNull { it.name == raw } ?: TWO_COLUMN
    }
}

data class HomeGridAnchor(val mediaId: String, val offset: Int) {
    fun indexIn(ids: List<String>): Int = ids.indexOf(mediaId).coerceAtLeast(0)
}

/** 以实际用户滚动的 dp 累计；方向变化清掉反向余量，微动不触发。 */
class HomeHeaderScrollPolicy(private val thresholdDp: Float = 24f) {
    var collapsed: Boolean = false
        private set
    private var accumulated = 0f

    fun scroll(deltaDp: Float): Boolean {
        if (!deltaDp.isFinite() || deltaDp == 0f) return false
        if (accumulated * deltaDp < 0f) accumulated = 0f
        accumulated += deltaDp
        if (kotlin.math.abs(accumulated) < thresholdDp) return false
        val next = accumulated < 0f
        accumulated = 0f
        if (next == collapsed) return false
        collapsed = next
        return true
    }
}
