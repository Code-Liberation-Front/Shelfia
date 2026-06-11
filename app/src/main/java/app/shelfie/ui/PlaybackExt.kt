package app.shelfie.ui

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController

fun MediaController.playEpisode(itemId: String, episodeId: String) {
    setMediaItem(MediaItem.Builder().setMediaId("episode:$itemId:$episodeId").build())
    prepare()
    play()
}

fun MediaController.playTrack(itemId: String, trackIndex: Int) {
    setMediaItem(MediaItem.Builder().setMediaId("track:$itemId:$trackIndex").build())
    prepare()
    play()
}

fun episodeMediaId(itemId: String, episodeId: String): String = "episode:$itemId:$episodeId"

fun trackMediaId(itemId: String, trackIndex: Int): String = "track:$itemId:$trackIndex"
