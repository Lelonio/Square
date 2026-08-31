package dev.lelonio.square.data

/** Backend-independent item exposed to discovery surfaces. */
sealed interface DiscoveryItem {
    val id: String
    val title: String
    val subtitle: String
    val artworkUrl: String?

    data class Track(val value: CatalogTrack) : DiscoveryItem {
        override val id = value.uri
        override val title = value.name
        override val subtitle = value.artist
        override val artworkUrl = value.artworkUrl
    }

    data class Context(val value: SearchItem) : DiscoveryItem {
        override val id = value.uri
        override val title = value.title
        override val subtitle = value.subtitle
        override val artworkUrl = value.artworkUrl
    }
}

/** A named, independently loadable discovery shelf. */
data class RecommendationSection(
    val id: String,
    val title: String,
    val items: List<DiscoveryItem> = emptyList(),
    val source: String? = null,
    val isCached: Boolean = false,
    val error: DiscoveryError? = null,
)

sealed interface DiscoveryError {
    data object Unavailable : DiscoveryError
    data object Offline : DiscoveryError
    data object AuthenticationRequired : DiscoveryError
    data object Network : DiscoveryError
    data class Unknown(val message: String) : DiscoveryError
}

/** Strategy boundary intentionally free of ranking/personalisation policy. */
interface RecommendationSource {
    val id: String
    suspend fun sections(): List<RecommendationSection>
}

/** Aggregates independent sources so one unavailable source cannot blank Home. */
interface RecommendationRepository {
    suspend fun sections(): List<RecommendationSection>
}

class CompositeRecommendationRepository(
    private val sources: List<RecommendationSource>,
) : RecommendationRepository {
    override suspend fun sections(): List<RecommendationSection> =
        sources.flatMap { source ->
            runCatching { source.sections() }.getOrElse {
                listOf(
                    RecommendationSection(
                        id = "${source.id}:unavailable",
                        title = source.id,
                        source = source.id,
                        error = DiscoveryError.Unavailable,
                    ),
                )
            }
        }
}

/**
 * Adapts existing backend home shelves without embedding recommendation logic
 * in Compose. Phase 8 can replace this source with a ranked/personalised source.
 */
class BackendDiscoverySource(
    private val backend: dev.lelonio.square.backend.MusicBackend,
) : RecommendationSource {
    override val id: String = "backend:${backend.id.name.lowercase()}"

    override suspend fun sections(): List<RecommendationSection> =
        backend.homeRows().mapIndexedNotNull { index, row ->
            val items = row.tracks.map { DiscoveryItem.Track(it) } +
                row.items.map {
                    DiscoveryItem.Context(
                        SearchItem(it.uri, it.name, "", it.artworkUrl),
                    )
                }
            items.takeIf { it.isNotEmpty() }?.let {
                RecommendationSection(
                    id = "$id:$index",
                    title = row.title,
                    items = it,
                    source = id,
                )
            }
        }
}
