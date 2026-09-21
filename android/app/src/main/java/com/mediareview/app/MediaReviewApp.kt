package com.mediareview.app

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.mediareview.app.feature.v2.player.gsy.GsyPlayerInitializer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * MediaReview Android 应用入口。
 * 通过 Hilt 注入全局依赖(网络栈、DataStore、仓库等)。
 * 把带统一 Bearer 认证的 Coil ImageLoader 设为单例,使封面/雪碧图等
 * MediaReview 服务器图片请求自动携带 token(雪碧图文件接口需要认证)。
 */
@HiltAndroidApp
class MediaReviewApp : Application() {

    @Inject
    lateinit var imageLoader: ImageLoader

    override fun onCreate() {
        super.onCreate()
        Coil.setImageLoader(imageLoader)
        // Stage2.1：应用级 GSY 统一初始化，固定 Exo2PlayerManager 内核
        GsyPlayerInitializer.init(this)
    }
}
