package dev.lelonio.square.offline

fun List<DownloadRecord>.aggregateProgress(collectionId: String): DownloadBatchProgress {
    val selected = filter { collectionId in it.collectionIds }
    return DownloadBatchProgress(
        totalTracks = selected.size,
        completedTracks = selected.count { it.status == DownloadStatus.COMPLETED },
        failedTracks = selected.count { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.UNAVAILABLE },
        downloadedBytes = selected.sumOf { it.downloadedBytes.coerceAtLeast(0L) },
        totalBytes = selected.map { it.totalBytes }.filter { it > 0L }.sum(),
    )
}
