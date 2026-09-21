package com.mediareview.app.feature.v2.player.native.state

import com.shuyu.gsyvideoplayer.utils.GSYVideoType

/**
 * 画面比例状态（MediaReview UI 选择，映射到 GSY 官方 SCREEN_TYPE）。
 * 全部使用 GSY 提供的显示能力，不改变视频文件本身。
 */
class VideoScaleState {

    enum class ScaleMode(val label: String, val gsyShowType: Int) {
        /** 适应（GSY SCREEN_TYPE_DEFAULT） */
        FIT("适应", GSYVideoType.SCREEN_TYPE_DEFAULT),
        /** 裁剪（GSY SCREEN_TYPE_FULL：填满并裁剪溢出） */
        CROP("裁剪", GSYVideoType.SCREEN_TYPE_FULL),
        /** 填充（GSY SCREEN_MATCH_FULL：拉伸铺满） */
        FILL("填充", GSYVideoType.SCREEN_MATCH_FULL),
        R16_9("16:9", GSYVideoType.SCREEN_TYPE_16_9),
        R4_3("4:3", GSYVideoType.SCREEN_TYPE_4_3),
    }

    var mode = ScaleMode.FIT
        private set

    fun set(mode: ScaleMode) {
        this.mode = mode
    }
}
