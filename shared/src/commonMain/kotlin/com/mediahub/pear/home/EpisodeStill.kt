package com.mediahub.pear.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mediahub.pear.history.SE
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.attempt
import com.mediahub.pear.shell.LocalAppEnv

/**
 * The still to show on a card for a title, where the card is a series episode (Continue Watching, Up Next, the history
 * pages, the sidebar). For a series episode, a stored thumb that is really the show's backdrop or poster does not count;
 * when no still is left, the episode's own still is looked up once and kept on screen. Returns null while there is
 * nothing yet, and the caller then shows the backdrop or poster. Movies and series without an episode get [storedThumb]
 * back unchanged.
 */
@Composable
fun rememberEpisodeStill(item: MetaPreview, se: SE?, storedThumb: String?): String? {
    val env = LocalAppEnv.current
    val episodic = item.type == "series" && se != null
    val backdrop = item.backdropUrl
    val poster = item.posterUrl
    var still by remember(item.id, se, storedThumb) {
        mutableStateOf(storedThumb?.takeIf { it != backdrop && it != poster })
    }
    LaunchedEffect(item.id, se, storedThumb) {
        val at = se ?: return@LaunchedEffect
        if (!episodic || still != null) return@LaunchedEffect
        // The IMDb lookup is left out: TMDB has the stills, and TheTVDB is only a fallback for missing ones.
        val eps = attempt { env.data.episodes.load(item.id, "series", at.season) { null } }
        eps?.firstOrNull { it.id == at.episode }?.image?.let { still = it }
    }
    return if (episodic) still else storedThumb
}
