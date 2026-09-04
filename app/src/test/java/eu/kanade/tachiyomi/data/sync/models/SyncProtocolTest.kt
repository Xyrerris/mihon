package eu.kanade.tachiyomi.data.sync.models

import eu.kanade.tachiyomi.data.sync.MangaProgressFacts
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test

/**
 * The wire format, exercised the way the two ends of it will meet: encoded by one implementation
 * and decoded by another that may not be the same version, or the same author.
 */
class SyncProtocolTest {

    private inline fun <reified T> roundTrip(serializer: kotlinx.serialization.KSerializer<T>, value: T): T {
        return ProtoBuf.decodeFromByteArray(serializer, ProtoBuf.encodeToByteArray(serializer, value))
    }

    @Test
    fun `When a request is encoded expect every field back`() {
        val request = SyncRequest(
            deviceId = "5b3f0f6c-0c2e-4a1e-9a0a-3f0c1b2d4e5f",
            since = 1_700_000_000L,
            changes = listOf(
                SyncManga(
                    source = 42L,
                    url = "/manga/one",
                    chapters = listOf(
                        SyncChapter(url = "/c/1", read = true, lastPageRead = 17, bookmark = true, version = 3),
                    ),
                    history = listOf(SyncHistory(url = "/c/1", lastRead = 1_700_000L, readDuration = 90_000L)),
                    startedAt = 1_600_000L,
                    completedAt = 1_650_000L,
                    lastModifiedAt = 1_699_999L,
                ),
            ),
        )

        roundTrip(SyncRequest.serializer(), request) shouldBe request
    }

    @Test
    fun `When a response is encoded expect every field back`() {
        val response = SyncResponse(
            now = 1_700_000_001L,
            changes = listOf(SyncManga(source = 42L, url = "/manga/one")),
        )

        roundTrip(SyncResponse.serializer(), response) shouldBe response
    }

    @Test
    fun `When a date is unknown expect it to stay unknown rather than become zero`() {
        val manga = SyncManga(source = 1L, url = "/m/1", startedAt = null, completedAt = 500L)

        val decoded = roundTrip(SyncManga.serializer(), manga)

        decoded.startedAt shouldBe null
        decoded.completedAt shouldBe 500L
        decoded.progress shouldBe MangaProgressFacts(startedAt = null, completedAt = 500L)
    }

    @Test
    fun `When a first sync sends nothing expect an empty delta, not an absent one`() {
        val request = SyncRequest(deviceId = "device", since = 0L)

        val decoded = roundTrip(SyncRequest.serializer(), request)

        decoded.changes shouldBe emptyList()
        decoded.since shouldBe 0L
    }

    /** A manga entry as a later version of the protocol might write it: one field further on. */
    @Serializable
    private data class SyncMangaFromTheFuture(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(3) val chapters: List<SyncChapter> = emptyList(),
        @ProtoNumber(8) val somethingNew: String = "",
    )

    @Test
    fun `When the other end knows a field this one does not expect the rest to decode`() {
        val future = SyncMangaFromTheFuture(
            source = 7L,
            url = "/m/1",
            chapters = listOf(SyncChapter(url = "/c/1", read = true)),
            somethingNew = "a field added by whoever wrote the server",
        )

        val decoded = ProtoBuf.decodeFromByteArray(
            SyncManga.serializer(),
            ProtoBuf.encodeToByteArray(SyncMangaFromTheFuture.serializer(), future),
        )

        decoded.source shouldBe 7L
        decoded.url shouldBe "/m/1"
        decoded.chapters shouldBe listOf(SyncChapter(url = "/c/1", read = true))
    }
}
