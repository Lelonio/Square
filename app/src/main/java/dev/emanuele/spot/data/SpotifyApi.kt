package dev.emanuele.spot.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The subset of the Web API this client uses.
 *
 * The Web API supplies every piece of metadata — search, library, playlists,
 * artwork. It cannot supply audio; that is the native engine's job. Keeping the
 * two strictly separated is what stops the "metadata says one thing, player says
 * another" desync that plagues the existing clients.
 */
interface SpotifyApi {

    @GET("v1/me")
    suspend fun me(): UserDto

    @GET("v1/me/tracks")
    suspend fun savedTracks(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): PageDto<SavedTrackDto>

    @GET("v1/me/playlists")
    suspend fun playlists(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): PageDto<PlaylistDto>

    /**
     * A page of a playlist's tracks.
     *
     * `market` is not optional in practice. Without it the response carries each
     * track's *original* URI, and a large share of the catalogue is region-split:
     * the original is not playable here and the engine skips straight past it —
     * which looks exactly like a player that will not play anything. With a
     * market Spotify relinks each track to the copy licensed for this account,
     * and `is_playable` becomes meaningful.
     */
    @GET("v1/playlists/{id}/tracks")
    suspend fun playlistTracks(
        @Path("id") playlistId: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("market") market: String = "from_token",
    ): PageDto<PlaylistTrackDto>

    /**
     * The playlist's version stamp, and nothing else.
     *
     * `snapshot_id` changes whenever the contents do, so this one small request
     * answers "is the copy on disk still right?" — which is what lets a
     * thousand-track playlist open without re-reading a dozen pages of it.
     * `fields` keeps the response to the two values that matter instead of the
     * whole playlist with its first hundred tracks inside.
     */
    @GET("v1/playlists/{id}")
    suspend fun playlistSnapshot(
        @Path("id") playlistId: String,
        @Query("fields") fields: String = "snapshot_id,tracks(total)",
    ): PlaylistSnapshotDto

    /**
     * Albums and singles released recently.
     *
     * Not personalised — Spotify's own recommendation endpoints were closed to
     * new applications, so this is the closest thing still open, and it is the
     * catalogue's front page rather than the user's. What makes the home feed
     * personal is what surrounds it: their own top artists, their playlists and
     * what they actually played.
     */
    @GET("v1/browse/new-releases")
    suspend fun newReleases(@Query("limit") limit: Int = 12): NewReleasesDto

    /** Needs the `user-top-read` scope; 403 without it. */
    @GET("v1/me/top/artists")
    suspend fun topArtists(
        @Query("limit") limit: Int = 12,
        /** `short_term` is roughly the last month, which is what "lately" means. */
        @Query("time_range") timeRange: String = "short_term",
    ): PageDto<ArtistDto>

    /**
     * The account's own most-played tracks. Same scope as the artists above.
     *
     * The time range is the whole point of having both: `short_term` is what is
     * on repeat now, `long_term` is what the account has always come back to,
     * and they are rarely the same list.
     */
    @GET("v1/me/top/tracks")
    suspend fun topTracks(
        @Query("limit") limit: Int = 20,
        @Query("time_range") timeRange: String = "short_term",
    ): PageDto<TrackDto>

    /**
     * What the account played last, wherever it played it.
     *
     * Different from the app's own recent list, which only knows what was
     * played here: this is the phone, the desktop and the speaker together.
     * Needs `user-read-recently-played`.
     */
    @GET("v1/me/player/recently-played")
    suspend fun recentlyPlayed(@Query("limit") limit: Int = 30): PageDto<PlayHistoryDto>

    /**
     * An artist's most-played tracks.
     *
     * The access point serves playlists and albums as contexts, but an artist is
     * not a context — there is no track list to resolve — so this one has to go
     * through the Web API and therefore needs the user's own application.
     *
     * `from_token` picks the market from the logged-in account; without a market
     * the endpoint answers 400.
     */
    @GET("v1/artists/{id}/top-tracks")
    suspend fun artistTopTracks(
        @Path("id") artistId: String,
        @Query("market") market: String = "from_token",
    ): TopTracksDto

    @GET("v1/artists/{id}/albums")
    suspend fun artistAlbums(
        @Path("id") artistId: String,
        @Query("include_groups") groups: String = "album,single",
        @Query("limit") limit: Int = 20,
    ): PageDto<AlbumDto>

    /**
     * Every device the account can currently play on.
     *
     * The Web API rather than the access point: librespot's Connect state keeps
     * the cluster — which is exactly this list — inside its own event loop, with
     * no public accessor, so reading it there would mean patching the crate.
     *
     * Needs `user-read-playback-state`.
     */
    @GET("v1/me/player/devices")
    suspend fun devices(): DevicesDto

    /** Moves playback to another device. Needs `user-modify-playback-state`. */
    @PUT("v1/me/player")
    suspend fun transferPlayback(@Body request: TransferRequestDto)

    /**
     * Appends tracks to a playlist. Needs `playlist-modify-private` for the
     * user's own private playlists and `playlist-modify-public` for their
     * public ones — which one applies is the playlist's visibility, not the
     * caller's, so both are asked for.
     */
    @POST("v1/playlists/{id}/tracks")
    suspend fun addToPlaylist(
        @Path("id") playlistId: String,
        @Body request: AddTracksRequestDto,
    )

    /**
     * Removes every occurrence of the given tracks from a playlist.
     *
     * Every occurrence: pinning it to one position needs the playlist's
     * snapshot id, and a playlist holding the same track twice is rare enough
     * that carrying snapshot state around for it is not worth the failure modes.
     *
     * `@HTTP` rather than `@DELETE` because this one carries a body, which
     * Retrofit's `@DELETE` does not allow.
     */
    @HTTP(method = "DELETE", path = "v1/playlists/{id}/tracks", hasBody = true)
    suspend fun removeFromPlaylist(
        @Path("id") playlistId: String,
        @Body request: RemoveTracksRequestDto,
    )

    @GET("v1/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("type") type: String = "track,album,artist,playlist",
        @Query("limit") limit: Int = 20,
    ): SearchDto
}

@Serializable
data class AddTracksRequestDto(val uris: List<String>)

@Serializable
data class RemoveTracksRequestDto(val tracks: List<TrackUriDto>)

@Serializable
data class TrackUriDto(val uri: String)

@Serializable
data class PageDto<T>(
    val items: List<T>,
    val total: Int = 0,
    val next: String? = null,
)

@Serializable
data class UserDto(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    /** `premium` or `free`; the engine only works for the former. */
    val product: String? = null,
    val images: List<ImageDto> = emptyList(),
)

@Serializable
data class SavedTrackDto(
    @SerialName("added_at") val addedAt: String? = null,
    val track: TrackDto,
)

@Serializable
data class PlaylistSnapshotDto(
    @SerialName("snapshot_id") val snapshotId: String? = null,
    val tracks: TotalDto? = null,
)

@Serializable
data class TotalDto(val total: Int = 0)

@Serializable
data class PlaylistTrackDto(
    /** Null for episodes and for tracks removed from the catalogue. */
    val track: TrackDto? = null,
    /** ISO-8601, and absent on playlists old enough to predate the field. */
    @SerialName("added_at") val addedAt: String? = null,
)

@Serializable
data class TrackDto(
    val id: String? = null,
    val uri: String,
    val name: String,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("is_playable") val isPlayable: Boolean? = null,
    val explicit: Boolean = false,
    val artists: List<ArtistDto> = emptyList(),
    val album: AlbumDto? = null,
)

@Serializable
data class ArtistDto(
    val id: String? = null,
    val uri: String? = null,
    val name: String,
    val images: List<ImageDto> = emptyList(),
)

@Serializable
data class AlbumDto(
    val id: String? = null,
    val uri: String? = null,
    val name: String,
    val images: List<ImageDto> = emptyList(),
    /** ISO date, and not always a full one — Spotify returns bare years too. */
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("total_tracks") val totalTracks: Int = 0,
    /** Present on browse and search results, absent when an album is nested in a track. */
    val artists: List<ArtistDto> = emptyList(),
)

@Serializable
data class TopTracksDto(val tracks: List<TrackDto> = emptyList())

/** One entry of the account's play history: the track and where it was played from. */
@Serializable
data class PlayHistoryDto(
    val track: TrackDto,
    @SerialName("played_at") val playedAt: String? = null,
    val context: PlayContextDto? = null,
)

@Serializable
data class PlayContextDto(val uri: String? = null, val type: String? = null)

@Serializable
data class NewReleasesDto(val albums: PageDto<AlbumDto>? = null)

@Serializable
data class DevicesDto(val devices: List<DeviceDto> = emptyList())

@Serializable
data class DeviceDto(
    /** Null for a device the account can see but not address, which is not playable. */
    val id: String? = null,
    val name: String,
    /** `Computer`, `Smartphone`, `Speaker`, `TV`, … */
    val type: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_restricted") val isRestricted: Boolean = false,
    @SerialName("volume_percent") val volumePercent: Int? = null,
)

@Serializable
data class TransferRequestDto(
    @SerialName("device_ids") val deviceIds: List<String>,
    /**
     * Keeps playing after the move. False would transfer *and* pause, which is
     * never what picking a device from a player means.
     */
    val play: Boolean = true,
)

@Serializable
data class PlaylistDto(
    val id: String,
    val uri: String,
    val name: String,
    val description: String? = null,
    val images: List<ImageDto> = emptyList(),
    val tracks: PlaylistTracksRefDto? = null,
)

@Serializable
data class PlaylistTracksRefDto(val total: Int = 0)

@Serializable
data class ImageDto(val url: String, val width: Int? = null, val height: Int? = null)

@Serializable
data class SearchDto(
    val tracks: PageDto<TrackDto>? = null,
    val albums: PageDto<AlbumDto>? = null,
    val artists: PageDto<ArtistDto>? = null,
    val playlists: PageDto<PlaylistDto?>? = null,
)
