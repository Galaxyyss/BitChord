package com.music.bitchord.data.discord

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.normalizedArtistName
import com.music.bitchord.data.scrobbling.primaryArtist
import com.music.bitchord.data.sources.TrackMatcher

/** Online cover lookup for local tracks; never sends local image data. */
internal object DiscordArtwork {
    private const val CACHE_SIZE = 128
    private data class Key(
        val title: String, val artist: String, val album: String?, val duration: String?,
        val explicit: Boolean?, val video: Boolean,
    )
    private val cache = object : LinkedHashMap<Key, String>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>?): Boolean = size > CACHE_SIZE
    }

    private fun key(song: Song) = Key(
        song.title, song.artist, song.albumName, song.durationText, song.isExplicit, song.isVideo,
    )

    fun cached(song: Song): String? = synchronized(cache) { cache[key(song)] }

    suspend fun resolve(song: Song): String? {
        cached(song)?.let { return it }
        if (song.title.isBlank() || song.artist.isBlank() || song.artist.equals("Unknown Artist", ignoreCase = true)) return null
        val target = TrackMatcher.targetOf(song)
        val base = TrackMatcher.queries(target).firstOrNull() ?: return null
        val query = listOfNotNull(base, song.albumName?.takeIf { it.isNotBlank() }).joinToString(" ")
        val results = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull() ?: return null
        val candidates = results.mapNotNull {
            when (it) {
                is SearchResult.Track -> it.song
                is SearchResult.TopTrack -> it.song
                is SearchResult.Browse -> null
            }
        }
        val url = select(song, candidates) ?: return null
        synchronized(cache) { cache[key(song)] = url }
        return url
    }

    internal fun select(song: Song, candidates: List<Song>): String? {
        val target = TrackMatcher.targetOf(song)
        val wantedAlbum = song.albumName?.let(::albumKey)?.takeIf { it.isNotEmpty() }
        val matches = TrackMatcher.ranked(candidates.filter { candidate ->
            TrackMatcher.sharesArtist(song.artist.primaryArtist(), candidate.artist.primaryArtist()) &&
                candidate.thumbnailUrl?.let(::isWebUrl) == true &&
                (wantedAlbum == null || candidate.albumName?.let(::albumKey) == wantedAlbum)
        }, target)
        if (wantedAlbum == null &&
            (matches.map { it.albumName?.let(::albumKey) }.distinct().size > 1 ||
                matches.map { it.thumbnailUrl }.distinct().size > 1)
        ) return null
        return matches.firstOrNull()?.thumbnailUrl
    }

    fun isWebUrl(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

    private fun albumKey(value: String): String =
        normalizedArtistName(value).filter { it.isLetterOrDigit() }
}
