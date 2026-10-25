package com.mediareview.app.feature.v2.home

enum class HomeDisplayMode(val columns: Int) {
    ONE_COLUMN(1), TWO_COLUMN(2);

    companion object {
        fun fromStored(raw: String?): HomeDisplayMode = entries.firstOrNull { it.name == raw } ?: TWO_COLUMN
    }
}

enum class HomeOverflowAction(val label: String) {
    SORT_FILTER("排序与筛选"),
    TOGGLE_LAYOUT(""),
    DATA_SOURCE("数据源"),
    BROWSE_FOLDERS("浏览文件夹"),
}

data class HomeOverflowItem(val action: HomeOverflowAction, val label: String)

fun homeOverflowActions(mode: HomeDisplayMode): List<HomeOverflowItem> = listOf(
    HomeOverflowItem(HomeOverflowAction.SORT_FILTER, HomeOverflowAction.SORT_FILTER.label),
    HomeOverflowItem(
        HomeOverflowAction.TOGGLE_LAYOUT,
        if (mode == HomeDisplayMode.TWO_COLUMN) "切换为单列" else "切换为双列",
    ),
    HomeOverflowItem(HomeOverflowAction.DATA_SOURCE, HomeOverflowAction.DATA_SOURCE.label),
    HomeOverflowItem(HomeOverflowAction.BROWSE_FOLDERS, HomeOverflowAction.BROWSE_FOLDERS.label),
)

sealed interface FolderBackTarget {
    data class Parent(val folderId: String?) : FolderBackTarget
    data object Exit : FolderBackTarget
}

fun folderBackTarget(
    folderId: String?,
    parents: Map<String, String?>,
    implicitRootId: String?,
): FolderBackTarget {
    if (folderId == null) return FolderBackTarget.Exit
    val parent = parents[folderId]
    return FolderBackTarget.Parent(parent?.takeUnless { it == implicitRootId })
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
