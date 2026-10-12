package com.mediareview.app.feature.v2.player.native.tracks

import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer

/** 菜单与读取时内核绑定，不能将旧视频的轨道索引应用到新视频。 */
@OptIn(UnstableApi::class)
class AudioTrackSnapshot internal constructor(
    val menu: AudioTrackMenu,
    internal val player: IjkExo2MediaPlayer,
    internal val dataSource: String?,
    internal val groups: Map<Int, TrackGroup>,
)

/**
 * 使用 GSY 已经创建的 Exo2 内核。调用者必须从当前 host 的 manager 提供 player，
 * 并在源 Loading/Error/切换时隐藏菜单。读取和选择均在 UI 线程执行。
 * 仅修改 AUDIO 的 override，不 setUp/play/seek，不替换 TrackSelector 或创建播放器。
 */
@OptIn(UnstableApi::class)
class GsyAudioTrackAdapter(private val currentPlayer: () -> IjkExo2MediaPlayer?) {
    @MainThread
    fun read(sourceKey: String): AudioTrackSnapshot? {
        val player = currentPlayer() ?: return null
        val selector = player.trackSelector as? DefaultTrackSelector ?: return null
        if (!selector.isSetParametersSupported) return null
        val tracks = player.currentTracks ?: return null
        val groups = mutableMapOf<Int, TrackGroup>()
        val descriptors = tracks.groups.flatMapIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@flatMapIndexed emptyList()
            groups[groupIndex] = group.mediaTrackGroup
            (0 until group.length).map { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                AudioTrackDescriptor(
                    key = AudioTrackKey(groupIndex, trackIndex),
                    supported = group.isTrackSupported(trackIndex),
                    selected = group.isTrackSelected(trackIndex),
                    language = format.language,
                    label = format.label,
                    channels = format.channelCount,
                )
            }
        }
        return AudioTrackSnapshot(audioTrackMenu(sourceKey, descriptors), player, player.dataSource, groups)
    }

    /** true 仅表示内核接受选择参数；最终 selected 状态必须重新 read，不能乐观打勾。 */
    @MainThread
    fun select(snapshot: AudioTrackSnapshot, choice: AudioTrackKey, currentSourceKey: String): Boolean {
        if (snapshot.menu.sourceKey != currentSourceKey || snapshot.menu.choices.none { it.key == choice }) return false
        val player = currentPlayer() ?: return false
        if (player !== snapshot.player || player.dataSource != snapshot.dataSource) return false
        val selector = player.trackSelector as? DefaultTrackSelector ?: return false
        if (!selector.isSetParametersSupported) return false
        if (!selector.isSetParametersSupported) return false
        val group = player.currentTracks?.groups?.getOrNull(choice.groupIndex) ?: return false
        if (group.type != C.TRACK_TYPE_AUDIO || choice.trackIndex !in 0 until group.length ||
            !group.isTrackSupported(choice.trackIndex) || group.mediaTrackGroup != snapshot.groups[choice.groupIndex]
        ) return false
        selector.setParameters(selector.buildUponParameters()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, choice.trackIndex))
            .build())
        return true
    }
}
