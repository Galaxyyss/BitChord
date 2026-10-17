package com.music.bitchord.playback
import android.content.Context
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.RemoteArtworkStore
import com.music.bitchord.ui.player.IncomingArtworkInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
/**
 * Prefetches artwork resources for upcoming tracks alongside the existing
 * audio prefetch.  Hooks into PlaybackService.prefetchAround() so it runs
 * on the same trigger and respects the same gating (metered connection, etc.).
 */
object ArtworkPrefetcher {
    private var scope: CoroutineScope? = null
        set(value) {
            field?.cancel()
            field = value
        }
    fun attachScope(s: CoroutineScope?) {
        scope = s
    }
    fun warmUp(
        context: Context,
        upcoming: List<Song>,
        incomingInfo: IncomingArtworkInfo?,
        meteredConnection: Boolean?,
    ) {
        if (meteredConnection == true) return
        // Skip prefetch on metered connections unless the user explicitly allows it.
        val s = scope ?: return
        s.launch(Dispatchers.IO + SupervisorJob()) {
            for (song in upcoming) {
                try { RemoteArtworkStore.resolveArt(context, song) } catch (_: Throwable) {}
            }
            incomingInfo?.song?.let { incomingSong ->
                try { RemoteArtworkStore.resolveArt(context, incomingSong) } catch (_: Throwable) {}
            }
        }
    }

    fun shutdown() {
        scope = null
    }
}
