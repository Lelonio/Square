package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Thumbnails(
    // LOCAL CHANGE: absent for a picture YouTube has none of, as on a playlist
    // with nothing in it yet. Required, that one item failed the whole page it
    // was on: a library with an empty playlist showed no playlists at all.
    val thumbnails: List<Thumbnail> = emptyList(),
)

@Serializable
data class Thumbnail(
    val url: String,
    val width: Int?,
    val height: Int?,
)
