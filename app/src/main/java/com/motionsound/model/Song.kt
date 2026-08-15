package com.motionsound.model

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val albumArtUri: String?,
    val uri: String,
    val dateAdded: Long = 0L
)
