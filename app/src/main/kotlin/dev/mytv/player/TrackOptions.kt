package dev.mytv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/** Une piste proposée dans le panneau du lecteur ; [group] null = « Désactivés » (sous-titres). */
class TrackChoice(val label: String, val group: Tracks.Group?, val index: Int)

/** Pistes audio lisibles du média en cours. */
fun Player.audioChoices(): List<TrackChoice> = choices(C.TRACK_TYPE_AUDIO)

/** « Désactivés » puis les sous-titres lisibles. */
fun Player.textChoices(): List<TrackChoice> = listOf(TrackChoice("Désactivés", null, -1)) + choices(C.TRACK_TYPE_TEXT)

/** Position de la piste actuellement jouée dans [list] (0 si aucune). */
fun Player.selectedIndex(list: List<TrackChoice>): Int {
    val i = list.indexOfFirst { c -> c.group?.isTrackSelected(c.index) == true }
    return if (i >= 0) i else 0
}

/**
 * Applique le choix. La langue choisie devient aussi la langue préférée : l'épisode suivant
 * démarre directement avec la même voix / les mêmes sous-titres.
 */
fun Player.select(choice: TrackChoice, trackType: Int) {
    val b = trackSelectionParameters.buildUpon()
    val group = choice.group
    if (group == null) {
        b.setTrackTypeDisabled(trackType, true)
    } else {
        val language = group.getTrackFormat(choice.index).language
        b.setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, choice.index))
        if (trackType == C.TRACK_TYPE_AUDIO) b.setPreferredAudioLanguage(language) else b.setPreferredTextLanguage(language)
    }
    trackSelectionParameters = b.build()
}

private fun Player.choices(type: Int): List<TrackChoice> {
    val out = ArrayList<TrackChoice>()
    currentTracks.groups.filter { it.type == type }.forEach { group ->
        for (i in 0 until group.length) {
            if (group.isTrackSupported(i)) out += TrackChoice(label(group.getTrackFormat(i), out.size + 1, type), group, i)
        }
    }
    return out
}

@OptIn(UnstableApi::class)
private fun label(f: Format, n: Int, type: Int): String {
    val language = f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED && it.isNotBlank() }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.FRENCH) }
        ?.takeIf { it.isNotBlank() }
        ?.replaceFirstChar { it.titlecase(Locale.FRENCH) }
    val base = f.label?.takeIf { it.isNotBlank() } ?: language ?: "Piste $n"
    val extra = buildList {
        if (type == C.TRACK_TYPE_AUDIO) when (f.channelCount) {
            1 -> add("mono"); 2 -> add("stéréo"); 6 -> add("5.1"); 8 -> add("7.1")
        }
        if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add("forcés")
    }
    return if (extra.isEmpty()) base else "$base (${extra.joinToString(", ")})"
}
