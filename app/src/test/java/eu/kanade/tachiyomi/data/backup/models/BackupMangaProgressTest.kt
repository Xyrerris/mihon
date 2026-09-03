package eu.kanade.tachiyomi.data.backup.models

import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test

class BackupMangaProgressTest {

    /**
     * A manga entry as it was written before the progress field existed: the same message, without
     * field 113. Decoding one as a [BackupManga] is what every backup already on a user's disk
     * does.
     */
    @Serializable
    private class BackupMangaWithoutProgress(
        @ProtoNumber(1) var source: Long,
        @ProtoNumber(2) var url: String,
        @ProtoNumber(3) var title: String = "",
    )

    @Test
    fun `When a backup carries the progress facts expect them back unchanged`() {
        val manga = BackupManga(
            source = 1L,
            url = "/m/1",
            title = "Manga",
            progress = BackupMangaProgress(startedAt = 1_000L, completedAt = 2_000L),
        )

        val decoded = ProtoBuf.decodeFromByteArray(
            BackupManga.serializer(),
            ProtoBuf.encodeToByteArray(BackupManga.serializer(), manga),
        )

        decoded.progress shouldBe BackupMangaProgress(startedAt = 1_000L, completedAt = 2_000L)
    }

    @Test
    fun `When one fact is unknown expect it to stay unknown`() {
        val manga = BackupManga(
            source = 1L,
            url = "/m/1",
            progress = BackupMangaProgress(startedAt = 1_000L, completedAt = null),
        )

        val decoded = ProtoBuf.decodeFromByteArray(
            BackupManga.serializer(),
            ProtoBuf.encodeToByteArray(BackupManga.serializer(), manga),
        )

        decoded.progress?.startedAt shouldBe 1_000L
        decoded.progress?.completedAt shouldBe null
    }

    @Test
    fun `When a backup predates the field expect it to restore as nothing to merge`() {
        val legacy = BackupMangaWithoutProgress(source = 1L, url = "/m/1", title = "Manga")

        val decoded = ProtoBuf.decodeFromByteArray(
            BackupManga.serializer(),
            ProtoBuf.encodeToByteArray(BackupMangaWithoutProgress.serializer(), legacy),
        )

        decoded.title shouldBe "Manga"
        decoded.progress shouldBe null
    }
}
