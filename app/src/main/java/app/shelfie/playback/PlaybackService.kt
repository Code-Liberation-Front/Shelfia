package app.shelfie.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
private const val PODCAST_PREFIX = "podcast:"
private const val EPISODE_PREFIX = "episode:"

@UnstableApi
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val repo get() = (application as ShelfieApp).repository

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
        startProgressSync()
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
        player.release()
        super.onDestroy()
    }

    // region progress sync

    private fun startProgressSync() {
        serviceScope.launch {
            while (isActive) {
                delay(15_000)
                if (player.isPlaying) pushProgress()
            }
        }
    }

    /** Reads player state on the main thread, then reports progress to the server. */
    private suspend fun pushProgress() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (!mediaId.startsWith(EPISODE_PREFIX)) return
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_ENDED) return
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3) return
        val positionSec = player.currentPosition / 1000.0
        val durationMs = player.duration
        val durationSec = if (durationMs != C.TIME_UNSET) durationMs / 1000.0 else 0.0
        withContext(Dispatchers.IO) {
            runCatching {
                if (repo.ensureConfigured()) {
                    repo.updateProgress(parts[1], parts[2], positionSec, durationSec)
                }
            }
        }
    }

    // endregion

    // region browse tree / item resolution

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
                        .setTitle("Podcasts")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
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
                            parentId == ROOT_ID ->
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
                val resolved = withContext(Dispatchers.IO) {
                    mediaItems.mapNotNull { runCatching { resolveEpisode(it.mediaId) }.getOrNull() }
                }
                val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
                var position = startPositionMs
                if (position == C.TIME_UNSET && resolved.isNotEmpty()) {
                    position = withContext(Dispatchers.IO) { savedPositionMs(resolved[index].mediaId) }
                }
                MediaSession.MediaItemsWithStartPosition(resolved, index, position)
            }
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
                    .build(),
            )
            .build()

    private fun episodeItem(
        podcast: LibraryItemExpanded,
        episode: PodcastEpisode,
        withUri: Boolean,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title ?: "Episode")
            .setArtist(podcast.media.metadata.title)
            .setAlbumTitle(podcast.media.metadata.title)
            .setArtworkUri(Uri.parse(repo.coverUrl(podcast.id)))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .build()
        val builder = MediaItem.Builder()
            .setMediaId("$EPISODE_PREFIX${podcast.id}:${episode.id}")
            .setMediaMetadata(metadata)
        if (withUri) {
            repo.streamUrl(podcast.id, episode)?.let { builder.setUri(it) }
        }
        return builder.build()
    }

    // endregion
}
