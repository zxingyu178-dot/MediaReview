package com.mediareview.app.feature.v2

import androidx.navigation.NavHostController
import com.mediareview.app.feature.v2.home.V2MainTab
import com.mediareview.app.feature.v2.model.V2Media

/** V2 统一导航入口：不感知当前 Tab，媒体类型自动路由。 */
object MediaNavigator {
    const val ROUTE_HOME = "home"
    const val ROUTE_REVIEW = "review"
    const val ROUTE_FAVORITES = "favorites"
    const val ROUTE_ORGANIZE = "organize"
    const val ROUTE_FOLDER = "folder/{folderId}"
    const val ROUTE_ALBUM = "album/{albumId}"
    const val ROUTE_PLAYER = "player/{mediaId}"
    const val ROUTE_VIEWER = "viewer/{mediaId}"

    // 整理中心子页（Stage 8C §46）：详情隐藏 BottomNav，Back 回整理
    const val ROUTE_ORGANIZE_DELETE = "organize/delete"
    const val ROUTE_ORGANIZE_DUPLICATES = "organize/duplicates"
    const val ROUTE_ORGANIZE_DUPLICATE_COMPARE = "organize/duplicates/{groupId}"
    const val ROUTE_ORGANIZE_LIBRARIES = "organize/libraries"

    fun folder(folderId: String) = "folder/$folderId"
    fun album(albumId: String) = "album/$albumId"
    fun player(mediaId: String) = "player/$mediaId"
    fun viewer(mediaId: String) = "viewer/$mediaId"

    fun organizeDelete() = ROUTE_ORGANIZE_DELETE
    fun organizeDuplicates() = ROUTE_ORGANIZE_DUPLICATES

    /** 分组 ID 只含 `[a-z0-9:_-]`（如 `exact:1000:60000:1`），冒号在路径段内合法。 */
    fun organizeDuplicateCompare(groupId: String) = "organize/duplicates/$groupId"

    fun organizeLibraries() = ROUTE_ORGANIZE_LIBRARIES

    /**
     * 统一打开媒体：VIDEO → Player，IMAGE → Viewer。
     * 收藏 / 首页 / 文件夹 / 以后批阅页全部走这里，不再在页面里复制 if/else。
     */
    fun openMedia(nav: NavHostController, media: V2Media) {
        if (media.isVideo) {
            nav.navigate(player(media.id))
        } else {
            nav.navigate(viewer(media.id))
        }
    }

    fun openFolder(nav: NavHostController, folderId: String) {
        nav.navigate(folder(folderId))
    }

    fun navigateAlbum(nav: NavHostController, albumId: String) {
        nav.navigate(album(albumId))
    }

    /** 底部导航一级 Tab 路由。 */
    fun tabRoute(tab: V2MainTab): String = when (tab) {
        V2MainTab.HOME -> ROUTE_HOME
        V2MainTab.REVIEW -> ROUTE_REVIEW
        V2MainTab.FAVORITES -> ROUTE_FAVORITES
        V2MainTab.ORGANIZE -> ROUTE_ORGANIZE
    }

    /** Player / Viewer 等详情页不显示底部导航。 */
    fun isTabRoute(currentRoute: String?): Boolean =
        currentRoute == ROUTE_HOME || currentRoute == ROUTE_REVIEW ||
            currentRoute == ROUTE_FAVORITES || currentRoute == ROUTE_ORGANIZE
}