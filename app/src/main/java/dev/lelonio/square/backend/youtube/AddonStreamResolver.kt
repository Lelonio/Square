package dev.lelonio.square.backend.youtube

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import dev.lelonio.square.data.sources.AddonPlayback
import dev.lelonio.square.data.sources.AddonSourceStore
import dev.lelonio.square.data.sources.AddonSourceClient

@UnstableApi
class AddonStreamResolver(private val context: android.content.Context) : ResolvingDataSource.Resolver {
    private val store = AddonSourceStore(context)

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri.toString()
        val parsed = AddonPlayback.parse(uri) ?: return dataSpec
        val source = store.sources.value.firstOrNull { it.id == parsed.first && it.enabled } ?: return dataSpec
        val stream = kotlinx.coroutines.runBlocking { AddonSourceClient(source).stream(
            dev.lelonio.square.data.sources.AddonTrack(id = parsed.second, title = "", artist = "")
        ) } ?: return dataSpec
        val resolved = dataSpec.withUri(android.net.Uri.parse(stream.url))
        if (stream.headers.isEmpty()) return resolved
        val headers = resolved.httpRequestHeaders + stream.headers
        return resolved.buildUpon().setHttpRequestHeaders(headers).build()
    }
}
