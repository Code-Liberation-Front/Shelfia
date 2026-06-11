package app.shelfie.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.shelfie.ShelfieApp
import app.shelfie.data.LibraryItemExpanded
import app.shelfie.data.LibraryItemSummary
import app.shelfie.data.PodcastEpisode
import app.shelfie.ui.MainActivity
import com.google.android.gms.cast.framework.CastContext
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ROOT_ID = "root"
private const val CONTINUE_ID = "continue"
private const val PODCASTS_ID = "podcasts"
private const val PODCAST_PREFIX = "podcast:"
private const val EPISODE_PREFIX = "episode:"

// Android Auto content-style hints (androidx.media legacy extras understood by Auto).
private const val EXTRA_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
private const val EXTRA_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
private const val STYLE_LIST = 1
private const val STYLE_GRID = 2

// Android Auto completion badges on playable items.
private const val EXTRA_COMPLETION_STATUS = "android.media.extra.PLAYBACK_STATUS"
private const val EXTRA_COMPLETION_PERCENTAGE = "androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE"
private const val STATUS_NOT_PLAYED = 0
private const val STATUS_PARTIALLY_PLAYED = 1
private const val STATUS_FULLY_PLAYED = 2

@UnstableApi
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private var castPlayer: CastPlayer? = null
    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val activePlayer: Player? get() = mediaSession?.player

    private val app get() = application as ShelfieApp
    private val repo get() = app.repository

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    serviceScope.launch { pushProgress() }
                }
            }
        })
        initCast()
        startProgressSync()
    }

    /** Chromecast support: swaps the session's player when a cast session starts/ends. */
    private fun initCast() {
        runCatching {
            val castContext = CastContext.getSharedInstance(this)
            castPlayer = CastPlayer(castContext, DefaultMediaItemConverter(), 10_000, 30_000).apply {
                setSessionAvailabilityListener(object : SessionAvailabilityListener {
                    override fun onCastSessionAvailable() = switchPlayer(this@apply)
                    override fun onCastSessionUnavailable() = switchPlayer(player)
                })
            }
        }
    }

    private fun switchPlayer(newPlayer: Player) {
        val session = mediaSession ?: return
        val old = session.player
        if (old === newPlayer) return
        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val position = old.currentPosition
        val playWhenReady = old.playWhenReady
        session.player = newPlayer
        if (items.isNotEmpty()) {
            newPlayer.setMediaItems(items, index, position)
            newPlayer.playWhenReady = playWhenReady
            newPlayer.prepare()
        }
        old.stop()
        old.clearMediaItems()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        castPlayer = null
        player.release()
        super.onDestroy()
    }

    // region progress sync

    private fun startProgressSync() {
        serviceScope.launch {
            while (isActive) {
                delay(15_000)
                if (activePlayer?.isPlaying == true) pushProgress()
            }
        }
    }

    /** Reads player state on the main thread, then reports progress to the server. */
    private suspend fun pushProgress() {
        val current = activePlayer ?: return
        val mediaId = current.currentMediaItem?.mediaId ?: return
        if (!mediaId.startsWith(EPISODE_PREFIX)) return
        if (current.playbackState != Player.STATE_READY && current.playbackState != Player.STATE_ENDED) return
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3) return
        val positionMs = current.currentPosition
        val durationMs = current.duration
        val durationSec = if (durationMs != C.TIME_UNSET) durationMs / 1000.0 else 0.0
        app.settings.saveLastPlayed(mediaId, positionMs)
        withContext(Dispatchers.IO) {
            runCatching {
                if (repo.ensureConfigured()) {
                    repo.updateProgress(parts[1], parts[2], positionMs / 1000.0, durationSec)
                }
            }
        }
    }

    // endregion

    // region browse tree / search / item resolution

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Shelfie")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build(),
                )
                .build()
            val rootExtras = Bundle().apply {
                putInt(EXTRA_STYLE_BROWSABLE, STYLE_GRID)
                putInt(EXTRA_STYLE_PLAYABLE, STYLE_LIST)
            }
            val rootParams = MediaLibraryService.LibraryParams.Builder().setExtras(rootExtras).build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            serviceScope.future {
                try {
                    if (!repo.ensureConfigured()) {
                        return@future LibraryResult.ofError<ImmutableList<MediaItem>>(
                            LibraryResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED,
                        )
                    }
                    val children: List<MediaItem> = withContext(Dispatchers.IO) {
                        when {
                            parentId == ROOT_ID -> rootTabs()

                            parentId == CONTINUE_ID ->
                                repo.continueListening().map {
                                    episodeItem(it.podcast, it.episode, withUri = false)
                                }

                            parentId == PODCASTS_ID ->
                                repo.podcasts().map { it.toBrowsableItem() }

                            parentId.startsWith(PODCAST_PREFIX) -> {
                                val itemId = parentId.removePrefix(PODCAST_PREFIX)
                                val podcast = repo.podcast(itemId)
                                podcast.media.episodes
                                    .sortedByDescending { it.publishedAt ?: 0 }
                                    .map { episodeItem(podcast, it, withUri = false) }
                            }

                            else -> emptyList()
                        }
                    }
                    LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
                } catch (e: Exception) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
                }
            }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            serviceScope.future {
                try {
                    val item = withContext(Dispatchers.IO) { resolveEpisode(mediaId) }
                    if (item != null) {
                        LibraryResult.ofItem(item, null)
                    } else {
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    }
                } catch (e: Exception) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
                }
            }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> =
            serviceScope.future {
                try {
                    val results = withContext(Dispatchers.IO) { searchItems(query) }
                    session.notifySearchResultChanged(browser, query, results.size, params)
                    LibraryResult.ofVoid()
                } catch (e: Exception) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
                }
            }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            serviceScope.future {
                try {
                    val results = withContext(Dispatchers.IO) { searchItems(query) }
                    LibraryResult.ofItemList(ImmutableList.copyOf(results), params)
                } catch (e: Exception) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
                }
            }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            serviceScope.future {
                withContext(Dispatchers.IO) {
                    mediaItems.mapNotNull { runCatching { resolveEpisode(it.mediaId) }.getOrNull() }
                        .toMutableList()
                }
            }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            serviceScope.future {
                withContext(Dispatchers.IO) {
                    // Voice queries ("play <podcast> on Shelfie") arrive as items with a
                    // search query instead of a media id.
                    val voiceQuery = mediaItems
                        .firstOrNull { !it.requestMetadata.searchQuery.isNullOrBlank() }
                        ?.requestMetadata?.searchQuery
                    val episodeIds = mediaItems.filter { it.mediaId.startsWith(EPISODE_PREFIX) }

                    when {
                        // Single episode tapped: queue the whole podcast so next/previous work.
                        episodeIds.size == 1 ->
                            podcastQueueFor(episodeIds[0].mediaId, startPositionMs)

                        episodeIds.isNotEmpty() -> {
                            val resolved = episodeIds.mapNotNull {
                                runCatching { resolveEpisode(it.mediaId) }.getOrNull()
                            }
                            val index = if (startIndex == C.INDEX_UNSET) 0
                            else startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
                            var position = startPositionMs
                            if (position == C.TIME_UNSET && resolved.isNotEmpty()) {
                                position = savedPositionMs(resolved[index].mediaId)
                            }
                            MediaSession.MediaItemsWithStartPosition(resolved, index, position)
                        }

                        !voiceQuery.isNullOrBlank() -> queueForVoiceQuery(voiceQuery)

                        else -> MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
                    }
                }
            }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            serviceScope.future {
                val (mediaId, storedPositionMs) = app.settings.lastPlayed()
                    ?: throw UnsupportedOperationException("Nothing to resume")
                withContext(Dispatchers.IO) {
                    val serverPositionMs = savedPositionMs(mediaId)
                    val position = if (serverPositionMs != C.TIME_UNSET) serverPositionMs else storedPositionMs
                    podcastQueueFor(mediaId, position)
                }
            }
    }

    private fun rootTabs(): List<MediaItem> = listOf(
        folderItem(
            id = CONTINUE_ID,
            title = "Continue Listening",
            extras = Bundle().apply { putInt(EXTRA_STYLE_PLAYABLE, STYLE_LIST) },
        ),
        folderItem(
            id = PODCASTS_ID,
            title = "Podcasts",
            extras = Bundle().apply { putInt(EXTRA_STYLE_BROWSABLE, STYLE_GRID) },
        ),
    )

    private fun folderItem(id: String, title: String, extras: Bundle): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
                    .setExtras(extras)
                    .build(),
            )
            .build()

    /** Browse-search and voice-search results: matching podcasts first, then episodes. */
    private suspend fun searchItems(query: String): List<MediaItem> {
        if (!repo.ensureConfigured()) return emptyList()
        val (podcastMatches, episodeMatches) = repo.search(query)
        return podcastMatches.map { it.toBrowsableItem() } +
            episodeMatches.map { (podcast, episode) -> episodeItem(podcast, episode, withUri = false) }
    }

    /** Builds a queue for a voice query: best episode match, else latest episode of the best podcast. */
    private suspend fun queueForVoiceQuery(query: String): MediaSession.MediaItemsWithStartPosition {
        if (!repo.ensureConfigured()) return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        val (podcastMatches, episodeMatches) = repo.search(query)
        val target: Pair<LibraryItemExpanded, PodcastEpisode>? = when {
            episodeMatches.isNotEmpty() -> episodeMatches.first()
            podcastMatches.isNotEmpty() -> {
                val podcast = repo.podcast(podcastMatches.first().id)
                podcast.media.episodes.maxByOrNull { it.publishedAt ?: 0 }?.let { podcast to it }
            }

            else -> null
        }
        val (podcast, episode) = target
            ?: return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        return podcastQueueFor("$EPISODE_PREFIX${podcast.id}:${episode.id}", C.TIME_UNSET)
    }

    /**
     * Builds the queue for a selected episode, resuming from the server-side
     * position when no explicit position was requested.
     *
     * Auto-play direction: picking an older episode continues forward in time
     * (e.g. 695 → 696 → 697); picking the newest episode walks backwards
     * (700 → 699 → 698). Either way the queue stops before the first episode
     * that has already been fully played. With auto-play disabled in settings,
     * only the selected episode is queued.
     */
    private suspend fun podcastQueueFor(
        mediaId: String,
        startPositionMs: Long,
    ): MediaSession.MediaItemsWithStartPosition {
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3 || !repo.ensureConfigured()) {
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        }
        val podcast = repo.podcast(parts[1])
        val autoPlay = runCatching { app.settings.autoPlayEnabled() }.getOrDefault(true)
        val episodes = if (autoPlay) {
            buildAutoPlayQueue(podcast, parts[2])
        } else {
            podcast.media.episodes.filter { it.id == parts[2] }
        }
        if (episodes.isEmpty()) {
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        }
        val queue = episodes.map { episodeItem(podcast, it, withUri = true) }
        var position = startPositionMs
        if (position == C.TIME_UNSET) {
            position = savedPositionMs(mediaId)
        }
        return MediaSession.MediaItemsWithStartPosition(queue, 0, position)
    }

    private suspend fun buildAutoPlayQueue(
        podcast: LibraryItemExpanded,
        episodeId: String,
    ): List<PodcastEpisode> {
        val chronological = podcast.media.episodes.sortedBy { it.publishedAt ?: 0 }
        val start = chronological.indexOfFirst { it.id == episodeId }
        if (start == -1) return emptyList()
        val direction = if (start == chronological.lastIndex) -1 else +1
        val queue = mutableListOf(chronological[start])
        var index = start + direction
        while (index in chronological.indices) {
            val episode = chronological[index]
            val finished = runCatching { repo.progress(podcast.id, episode.id) }
                .getOrNull()?.isFinished == true
            if (finished) break
            queue.add(episode)
            index += direction
        }
        return queue
    }

    /** Looks up the server-side resume position for an episode media id. */
    private suspend fun savedPositionMs(mediaId: String): Long {
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3) return C.TIME_UNSET
        val progress = runCatching { repo.progress(parts[1], parts[2]) }.getOrNull() ?: return C.TIME_UNSET
        if (progress.isFinished) return 0L
        return (progress.currentTime * 1000).toLong()
    }

    /** Turns an "episode:{itemId}:{episodeId}" media id into a fully playable MediaItem. */
    private suspend fun resolveEpisode(mediaId: String): MediaItem? {
        if (!mediaId.startsWith(EPISODE_PREFIX)) return null
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3) return null
        if (!repo.ensureConfigured()) return null
        val podcast = repo.podcast(parts[1])
        val episode = podcast.media.episodes.firstOrNull { it.id == parts[2] } ?: return null
        return episodeItem(podcast, episode, withUri = true)
    }

    private fun LibraryItemSummary.toBrowsableItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId("$PODCAST_PREFIX$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(media.metadata.title ?: "Podcast")
                    .setArtist(media.metadata.author)
                    .setArtworkUri(Uri.parse(repo.coverUrl(id)))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                    .setExtras(Bundle().apply { putInt(EXTRA_STYLE_PLAYABLE, STYLE_LIST) })
                    .build(),
            )
            .build()

    private suspend fun episodeItem(
        podcast: LibraryItemExpanded,
        episode: PodcastEpisode,
        withUri: Boolean,
    ): MediaItem {
        val extras = Bundle()
        val progress = runCatching { repo.progress(podcast.id, episode.id) }.getOrNull()
        when {
            progress == null || progress.currentTime <= 0 ->
                extras.putInt(EXTRA_COMPLETION_STATUS, STATUS_NOT_PLAYED)

            progress.isFinished ->
                extras.putInt(EXTRA_COMPLETION_STATUS, STATUS_FULLY_PLAYED)

            else -> {
                extras.putInt(EXTRA_COMPLETION_STATUS, STATUS_PARTIALLY_PLAYED)
                extras.putDouble(EXTRA_COMPLETION_PERCENTAGE, progress.progress.coerceIn(0.0, 1.0))
            }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title ?: "Episode")
            .setArtist(podcast.media.metadata.title)
            .setAlbumTitle(podcast.media.metadata.title)
            .setArtworkUri(Uri.parse(repo.coverUrl(podcast.id)))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .setExtras(extras)
            .build()
        val builder = MediaItem.Builder()
            .setMediaId("$EPISODE_PREFIX${podcast.id}:${episode.id}")
            .setMediaMetadata(metadata)
        if (withUri) {
            // Prefer the downloaded copy so playback works offline.
            val localUri = app.downloads.localUri(podcast.id, episode.id)
            if (localUri != null) {
                builder.setUri(localUri)
                builder.setMimeType(episode.audioFile?.mimeType ?: "audio/mpeg")
            } else {
                repo.streamUrl(podcast.id, episode)?.let { url ->
                    builder.setUri(url)
                    // The Cast media item converter requires a MIME type.
                    builder.setMimeType(episode.audioFile?.mimeType ?: "audio/mpeg")
                }
            }
        }
        return builder.build()
    }

    // endregion
}
