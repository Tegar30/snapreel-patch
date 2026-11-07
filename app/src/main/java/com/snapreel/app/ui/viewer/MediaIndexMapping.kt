package com.snapreel.app.ui.viewer

import com.snapreel.app.data.model.MediaItem

/**
 * Index mapping between the grid, the reels viewer (full list) and the landscape viewer (videos
 * only), all over one shared snapshot. The first three functions are the original inline logic,
 * moved unchanged; [resolveStartIndex] is new.
 */
object MediaIndexMapping {

    /** What the grid shows: every item, or only videos in landscape mode, optionally filtered by favorites. */
    fun gridDisplayedItems(
        mediaItems: List<MediaItem>,
        isLandscapeMode: Boolean,
        favoritesOnly: Boolean = false,
        favorites: Set<String> = emptySet()
    ): List<MediaItem> {
        val base = if (isLandscapeMode) mediaItems.filter { it.isVideo } else mediaItems
        return if (favoritesOnly) {
            base.filter { it.uri.toString() in favorites }
        } else {
            base
        }
    }

    /** A grid tap on [displayedIndex] → the full-list index passed to the viewer route. */
    fun gridTapToFullIndex(
        mediaItems: List<MediaItem>,
        isLandscapeMode: Boolean,
        displayedIndex: Int,
        favoritesOnly: Boolean = false,
        favorites: Set<String> = emptySet()
    ): Int {
        val displayedItems = gridDisplayedItems(mediaItems, isLandscapeMode, favoritesOnly, favorites)
        val item = displayedItems.getOrNull(displayedIndex) ?: return displayedIndex
        val originalIndex = mediaItems.indexOfFirst { it.uri == item.uri }
        return if (originalIndex >= 0) originalIndex else displayedIndex
    }

    /**
     * A full-list index → the landscape viewer's video index. A non-video in range opens video 0,
     * and an out-of-range index is clamped. Null when there are no videos.
     */
    fun fullToLandscapeIndex(
        allItems: List<MediaItem>,
        requestedFullIndex: Int,
        favoritesOnly: Boolean = false,
        favorites: Set<String> = emptySet()
    ): Int? {
        val videoList = allItems.filter { it.isVideo && (!favoritesOnly || it.uri.toString() in favorites) }
        if (videoList.isEmpty()) return null
        val targetVideoUri = allItems.getOrNull(requestedFullIndex)?.uri
        return if (targetVideoUri != null) {
            val found = videoList.indexOfFirst { it.uri == targetVideoUri }
            if (found >= 0) found else 0
        } else {
            requestedFullIndex.coerceIn(0, videoList.size - 1)
        }
    }

    /** The landscape viewer's video index → the full-list index saved on back. Null if out of range. */
    fun landscapeToFullIndex(
        allItems: List<MediaItem>,
        videoIndex: Int,
        favoritesOnly: Boolean = false,
        favorites: Set<String> = emptySet()
    ): Int? {
        val videos = allItems.filter { it.isVideo && (!favoritesOnly || it.uri.toString() in favorites) }
        val currentVideo = videos.getOrNull(videoIndex) ?: return null
        val originalIndex = allItems.indexOfFirst { it.uri == currentVideo.uri }
        return if (originalIndex >= 0) originalIndex else videoIndex
    }

    /**
     * The full-list index of the saved last item in [snapshot]: found by [savedItemUri] when it is
     * still there, otherwise [fallbackIndex] (the saved index, unchanged; callers clamp as before).
     */
    fun resolveStartIndex(snapshot: List<MediaItem>, savedItemUri: String?, fallbackIndex: Int): Int {
        if (savedItemUri.isNullOrEmpty()) return fallbackIndex
        val found = snapshot.indexOfFirst { it.uri.toString() == savedItemUri }
        return if (found >= 0) found else fallbackIndex
    }
}
