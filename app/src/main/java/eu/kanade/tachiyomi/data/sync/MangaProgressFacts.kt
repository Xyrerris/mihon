package eu.kanade.tachiyomi.data.sync

/**
 * What a merge has to carry about a manga's reading progress.
 *
 * Everything else on a manga_progress row -- the counts, the percentage, the resume point, the read
 * duration -- is derived from the chapters and the history a device already holds, so sending it
 * would only be sending a device its own arithmetic. These two are not derivable:
 *
 * - [startedAt] can predate the history that is still on the device, and history can be cleared.
 * - [completedAt] cannot be worked out at all by a device that has not finished the manga.
 *
 * Both are epoch milliseconds, and null means "this device does not know", never "it did not
 * happen": that is what makes the merge rule an earliest-wins minimum rather than a comparison.
 */
data class MangaProgressFacts(
    val startedAt: Long?,
    val completedAt: Long?,
)
