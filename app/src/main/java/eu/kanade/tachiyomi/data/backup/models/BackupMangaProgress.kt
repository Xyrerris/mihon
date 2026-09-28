package eu.kanade.tachiyomi.data.backup.models

import eu.kanade.tachiyomi.data.sync.MangaProgressFacts
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.manga.model.MangaProgress

/**
 * The two manga_progress columns a backup carries. Everything else on the row is derived from the
 * chapters and history the backup already holds, and is recomputed on the way in.
 *
 * Null means the device that wrote the backup did not know, which is also how a backup written
 * before this existed reads: absent, and merged as a no-op.
 */
@Serializable
data class BackupMangaProgress(
    @ProtoNumber(1) var startedAt: Long? = null,
    @ProtoNumber(2) var completedAt: Long? = null,
) {
    fun toFacts(): MangaProgressFacts = MangaProgressFacts(
        startedAt = this@BackupMangaProgress.startedAt,
        completedAt = this@BackupMangaProgress.completedAt,
    )
}

fun MangaProgress.toBackupMangaProgress() = BackupMangaProgress(
    startedAt = startedAt,
    completedAt = completedAt,
)
