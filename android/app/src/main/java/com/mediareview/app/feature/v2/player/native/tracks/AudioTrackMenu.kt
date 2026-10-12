package com.mediareview.app.feature.v2.player.native.tracks

import java.util.Locale

data class AudioTrackKey(val groupIndex: Int, val trackIndex: Int)

/** 只保存可显示的轨道资料；group/track 索引始终是内核原始索引。 */
data class AudioTrackDescriptor(
    val key: AudioTrackKey,
    val isAudio: Boolean = true,
    val supported: Boolean = true,
    val selected: Boolean = false,
    val language: String? = null,
    val label: String? = null,
    val channels: Int = 0,
)

data class AudioTrackChoice(val key: AudioTrackKey, val label: String, val selected: Boolean)

data class AudioTrackMenu(val sourceKey: String, val choices: List<AudioTrackChoice>) {
    val canSelect: Boolean get() = choices.size > 1
}

fun audioTrackMenu(sourceKey: String, tracks: List<AudioTrackDescriptor>): AudioTrackMenu =
    AudioTrackMenu(sourceKey, tracks.filter { it.isAudio && it.supported }.mapIndexed { index, track ->
        val parts = mutableListOf("音轨 ${index + 1}")
        track.label?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
        languageLabel(track.language)?.takeIf { it !in parts }?.let(parts::add)
        if (track.channels > 0) parts += "${track.channels} 声道"
        AudioTrackChoice(track.key, parts.joinToString(" · "), track.selected)
    })

private fun languageLabel(raw: String?): String? {
    val language = raw?.trim()?.lowercase(Locale.ROOT)?.takeUnless { it.isEmpty() || it == "und" }
        ?: return null
    val normalized = when (language) {
        "eng" -> "en"
        "jpn" -> "ja"
        "zho", "chi" -> "zh"
        "kor" -> "ko"
        "fra", "fre" -> "fr"
        "deu", "ger" -> "de"
        "spa" -> "es"
        else -> language.replace('_', '-')
    }
    return Locale.forLanguageTag(normalized).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)
        .takeIf { it.isNotBlank() && it != normalized }
}
