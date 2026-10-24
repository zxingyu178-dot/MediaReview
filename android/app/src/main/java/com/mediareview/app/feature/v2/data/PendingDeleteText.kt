package com.mediareview.app.feature.v2.data

/**
 * "加入待删除"被拒绝时的用户提示（Stage 9）。
 *
 * 产品语义：本地媒体源已放开"两阶段安全删除"，**是否可删除只信任服务端下发的
 * `can_delete`**，App 不再用 `loc_` 前缀或 `source_type` 自行判断，也不再宣称"只读"。
 *
 * 旧 Server（缺少能力 `native_delete_v1`）对本地源仍下发 `can_delete=false`，
 * 此处据此补充"升级电脑端 Server"的指引，避免用户误以为 App 故障。
 */
object PendingDeleteText {

    /** 服务端明确不可删除（例如 Jellyfin 媒体库未授权删除）。 */
    const val NOT_DELETABLE = "该媒体不可删除"

    /** 旧 Server 尚未支持本地源删除：提示升级，而不是宣称"本地只读"。 */
    const val NEED_SERVER_UPGRADE = "该媒体不可删除：需要升级电脑端 Server"

    /** 按服务端能力选择提示：能力缺失 = 旧 Server = 指引升级。 */
    fun rejected(supportsNativeDelete: Boolean): String =
        if (supportsNativeDelete) NOT_DELETABLE else NEED_SERVER_UPGRADE
}
