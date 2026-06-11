package app.shelfie.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.net.Uri
import android.os.Build
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
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.shelfie.R
import app.shelfie.ShelfieApp
import app.shelfie.data.BookTrack
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
private const val TRACK_PREFIX = "track:"

// Extras carried on audiobook track items for progress reporting.
private const val EXTRA_TRACK_START_OFFSET = "app.shelfie.trackStartOffset"
private const val EXTRA_BOOK_DURATION = "app.shelfie.bookDuration"

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

// Custom session commands so Android Auto shows the app's skip buttons.
private const val COMMAND_SKIP_BACK = "app.shelfie.SKIP_BACK_10"
private const val COMMAND_SKIP_FORWARD = "app.shelfie.SKIP_FORWARD_30"

@UnstableApi
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private var castPlayer: CastPlayer? = null
    private var mediaSession: MediaLibrarySession? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
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
        val skipBackButton = CommandButton.Builder()
            .setDisplayName("Back 10 seconds")
            .setIconResId(R.drawable.ic_skip_back_10)
            .setSessionCommand(SessionCommand(COMMAND_SKIP_BACK, Bundle.EMPTY))
            .build()
        val skipForwardButton = CommandButton.Builder()
            .setDisplayName("Forward 30 seconds")
            .setIconResId(R.drawable.ic_skip_forward_30)
            .setSessionCommand(SessionCommand(COMMAND_SKIP_FORWARD, Bundle.EMPTY))
            .build()
        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .setCustomLayout(listOf(skipBackButton, skipForwardButton))
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    serviceScope.launch { pushProgress() }
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                // The effect is bound to the audio session; re-attach on changes.
                serviceScope.launch {
                    applyNormalization(app.settings.normalizeAudioEnabled())
                }
            }
        })
        serviceScope.launch {
            app.settings.normalizeAudio.collect { enabled -> applyNormalization(enabled) }
        }
        initCast()
        startProgressSync()
    }

    /**
     * Peak normalization: a limiter (via DynamicsProcessing, Android 9+) on the
     * player's audio session that clamps volume spikes such as loud ad breaks.
     */
    private fun applyNormalization(enabled: Boolean) {
        runCatching { dynamicsProcessing?.release() }
        dynamicsProcessing = null
        if (!enabled || Build.VERSION.SDK_INT < 28) return
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                /* channelCount= */ 2,
                /* preEqInUse= */ false, /* preEqBandCount= */ 0,
                /* mbcInUse= */ false, /* mbcBandCount= */ 0,
                /* postEqInUse= */ false, /* postEqBandCount= */ 0,
                /* limiterInUse= */ true,
            ).build()
            dynamicsProcessing = DynamicsProcessing(0, sessionId, config).apply {
                val limiter = DynamicsProcessing.Limiter(
                    /* inUse= */ true,
                    /* enabled= */ true,
                    /* linkGroup= */ 0,
                    /* attackTime= */ 1f,
                    /* releaseTime= */ 60f,
                    /* ratio= */ 8f,
                    /* threshold= */ -14f,
                    /* postGain= */ 3f,
                )
                setLimiterAllChannelsTo(limiter)
                setEnabled(true)
            }
        }
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
        runCatching { dynamicsProcessing?.release() }
        dynamicsProcessing = null
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
        if (current.playbackState != Player.STATE_READY && current.playbackState != Player.STATE_ENDED) return
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3) return
        val positionMs = current.currentPosition
        app.settings.saveLastPlayed(mediaId, positionMs)
        when {
            mediaId.startsWith(EPISODE_PREFIX) -> {
                val durationMs = current.duration
                val durationSec = if (durationMs != C.TIME_UNSET) durationMs / 1000.0 else 0.0
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (repo.ensureConfigured()) {
                            repo.updateProgress(parts[1], parts[2], positionMs / 1000.0, durationSec)
                        }
                    }
                }
            }

            mediaId.startsWith(TRACK_PREFIX) -> {
                // Book progress is reported against the whole book timeline.
                val extras = current.currentMediaItem?.mediaMetadata?.extras
                val startOffset = extras?.getDouble(EXTRA_TRACK_START_OFFSET) ?: 0.0
                val bookDuration = extras?.getDouble(EXTRA_BOOK_DURATION) ?: 0.0
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (repo.ensureConfigured()) {
                            repo.updateProgress(parts[1], "", startOffset + positionMs / 1000.0, bookDuration)
                        }
                    }
                }
            }
        }
    }

    // endregion

    // region browse tree / search / item resolution

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(COMMAND_SKIP_BACK, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SKIP_FORWARD, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                COMMAND_SKIP_BACK -> {
                    activePlayer?.seekBack()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_SKIP_FORWARD -> {
                    activePlayer?.seekForward()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }

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
                    val item = withContext(Dispatchers.IO) { resolveAny(mediaId) }
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
                    mediaItems.mapNotNull { runCatching { resolveAny(it.mediaId) }.getOrNull() }
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
                    val trackIds = mediaItems.filter { it.mediaId.startsWith(TRACK_PREFIX) }

                    when {
                        // Audiobook track tapped: queue the whole book at that track.
                        trackIds.isNotEmpty() ->
                            bookQueueFor(trackIds[0].mediaId, startPositionMs)

                        // Single episode tapped: queue the whole podcast so next/previous work.
                        episodeIds.size == 1 ->
                            podcastQueueFor(episodeIds[0].mediaId, startPositionMs)

                        episodeIds.isNotEmpty() -> {
                            val resolved = episodeIds.mapNotNull {
                                runCatching { resolveAny(it.mediaId) }.getOrNull()
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
                    if (mediaId.startsWith(TRACK_PREFIX)) {
                        bookQueueFor(mediaId, storedPositionMs)
                    } else {
                        val serverPositionMs = savedPositionMs(mediaId)
                        val position = if (serverPositionMs != C.TIME_UNSET) serverPositionMs else storedPositionMs
                        podcastQueueFor(mediaId, position)
                    }
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
        val podcast = try {
            repo.podcast(parts[1])
        } catch (e: Exception) {
            // Offline with no cached metadata: fall back to the downloaded copy.
            val downloaded = downloadedEpisodeItem(parts[1], parts[2])
                ?: return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
            var position = startPositionMs
            if (position == C.TIME_UNSET) {
                position = savedPositionMs(mediaId)
            }
            return MediaSession.MediaItemsWithStartPosition(listOf(downloaded), 0, position)
        }
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

    private suspend fun resolveAny(mediaId: String): MediaItem? = when {
        mediaId.startsWith(EPISODE_PREFIX) -> resolveEpisode(mediaId)
        mediaId.startsWith(TRACK_PREFIX) -> resolveTrack(mediaId)
        else -> null
    }

    /** Turns a "track:{itemId}:{index}" media id into a fully playable MediaItem. */
    private suspend fun resolveTrack(mediaId: String): MediaItem? {
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3) return null
        if (!repo.ensureConfigured()) return null
        val book = repo.podcast(parts[1])
        val index = parts[2].toIntOrNull() ?: return null
        val track = book.media.tracks.getOrNull(index) ?: return null
        return trackItem(book, index, track)
    }

    /**
     * Queues an entire audiobook positioned at the selected track, resuming inside
     * that track from the server-side book position when none was requested.
     */
    private suspend fun bookQueueFor(
        mediaId: String,
        startPositionMs: Long,
    ): MediaSession.MediaItemsWithStartPosition {
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3 || !repo.ensureConfigured()) {
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        }
        val book = repo.podcast(parts[1])
        val tracks = book.media.tracks
        if (tracks.isEmpty()) {
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        }
        val startIndex = (parts[2].toIntOrNull() ?: 0).coerceIn(0, tracks.lastIndex)
        val queue = tracks.mapIndexed { index, track -> trackItem(book, index, track) }
        var position = startPositionMs
        if (position == C.TIME_UNSET) {
            val saved = runCatching { repo.bookProgress(parts[1]) }.getOrNull()
            val track = tracks[startIndex]
            val bookTime = saved?.currentTime ?: 0.0
            position = if (saved != null && !saved.isFinished &&
                bookTime >= track.startOffset && bookTime < track.startOffset + track.duration
            ) {
                ((bookTime - track.startOffset) * 1000).toLong()
            } else {
                0L
            }
        }
        return MediaSession.MediaItemsWithStartPosition(queue, startIndex, position)
    }

    private fun trackItem(book: LibraryItemExpanded, index: Int, track: BookTrack): MediaItem {
        val extras = Bundle().apply {
            putDouble(EXTRA_TRACK_START_OFFSET, track.startOffset)
            putDouble(EXTRA_BOOK_DURATION, book.media.duration)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title ?: "Part ${index + 1}")
            .setArtist(book.media.metadata.title)
            .setAlbumTitle(book.media.metadata.title)
            .setArtworkUri(Uri.parse(repo.coverUrl(book.id)))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .setExtras(extras)
            .build()
        val builder = MediaItem.Builder()
            .setMediaId("$TRACK_PREFIX${book.id}:$index")
            .setMediaMetadata(metadata)
        track.contentUrl?.let { contentUrl ->
            builder.setUri(repo.tokenizedUrl(contentUrl))
            builder.setMimeType("audio/mpeg")
        }
        return builder.build()
    }

    /**
     * Builds a playable item for a downloaded episode straight from the download
     * index, so offline playback never depends on the API cache (which can be
     * cleared by library switches or missing on some devices).
     */
    private fun downloadedEpisodeItem(itemId: String, episodeId: String): MediaItem? {
        val localUri = app.downloads.localUri(itemId, episodeId) ?: return null
        val entry = app.downloads.entry(itemId, episodeId) ?: return null
        val metadata = MediaMetadata.Builder()
            .setTitle(entry.title)
            .setArtist(entry.podcastTitle)
            .setAlbumTitle(entry.podcastTitle)
            .setArtworkUri(Uri.parse(repo.coverUrl(itemId)))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .build()
        return MediaItem.Builder()
            .setMediaId("$EPISODE_PREFIX$itemId:$episodeId")
            .setMediaMetadata(metadata)
            .setUri(localUri)
            .setMimeType("audio/mpeg")
            .build()
    }

    /** Turns an "episode:{itemId}:{episodeId}" media id into a fully playable MediaItem. */
    private suspend fun resolveEpisode(mediaId: String): MediaItem? {
        if (!mediaId.startsWith(EPISODE_PREFIX)) return null
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3) return null
        return try {
            if (!repo.ensureConfigured()) return downloadedEpisodeItem(parts[1], parts[2])
            val podcast = repo.podcast(parts[1])
            val episode = podcast.media.episodes.firstOrNull { it.id == parts[2] }
                ?: return downloadedEpisodeItem(parts[1], parts[2])
            episodeItem(podcast, episode, withUri = true)
        } catch (e: Exception) {
            downloadedEpisodeItem(parts[1], parts[2]) ?: throw e
        }
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
