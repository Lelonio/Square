package dev.emanuele.spot.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
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

    @GET("v1/playlists/{id}/tracks")
    suspend fun playlistTracks(
        @Path("id") playlistId: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): PageDto<PlaylistTrackDto>

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

    @GET("v1/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("type") type: String = "track,album,artist,playlist",
        @Query("limit") limit: Int = 20,
    ): SearchDto
}

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
data class PlaylistTrackDto(
    /** Null for episodes and for tracks removed from the catalogue. */
    val track: TrackDto? = null,
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

@Serializable
data class NewReleasesDto(val albums: PageDto<AlbumDto>? = null)

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
