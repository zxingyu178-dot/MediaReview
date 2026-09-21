package com.mediareview.app.feature.v2.player.gsy

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mediareview.app.feature.v2.model.V2Media
import com.shuyu.gsyvideoplayer.builder.GSYVideoOptionBuilder
import com.shuyu.gsyvideoplayer.compose.wrapper.GSYVideoPlayerView

/**
 * GSY 播放器页（Wrapper 模式，第一阶段不改 GSY 原生 UI）：
 * StandardGSYVideoPlayer 原生控件，全屏 / 手势 / 亮度 / 音量 / 锁定 / 倍速等全部由 GSY 提供。
 *
 * - setUpKey = mediaId：Compose 重组不会重复 setUp；
 * - autoPauseResume = true：自动桥接生命周期（退后台暂停、回前台恢复）；
 * - autoReleaseOnDispose = true：离开页面完全释放播放器，避免声音残留。
 */
@Composable
fun GsyPlayerScreen(
    media: V2Media,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val request = remember(media.id) {
        GsyPlaybackRequest(
            mediaId = media.id,
            title = media.name,
            url = demoRawVideoUri(context, media),
        )
    }
    BackHandler(onBack = onBack)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        GSYVideoPlayerView(
            modifier = Modifier.fillMaxSize(),
            setUp = { player ->
                GSYVideoOptionBuilder()
                    .setUrl(request.url)
                    .setMapHeadData(request.headers)
                    .setVideoTitle(request.title)
                    .setCacheWithPlay(false)
                    .build(player)
            },
            setUpKey = request.mediaId,
            autoReleaseOnDispose = true,
            autoPauseResume = true,
        )
    }
}

/**
 * Demo 本地视频 URI：res/raw → android.resource://<package>/raw/<name>。
 * 由 media.assetPath（"demo_media/videos/01_landscape.mp4"）推导 raw 资源名
 * （"demo_01_landscape"）。本阶段 6 个 Demo MP4 已从 assets 移至 res/raw。
 */
fun demoRawVideoUri(context: Context, media: V2Media): String {
    val file = media.assetPath.substringAfterLast('/')
    val base = file.removeSuffix(".mp4")
    return "android.resource://${context.packageName}/raw/demo_$base"
}
