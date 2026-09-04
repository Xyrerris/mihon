package eu.kanade.tachiyomi.data.sync.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * What the server answers with.
 *
 * [changes] is what the other devices have written past the request's `since`. The server does not
 * merge and does not need to know what a chapter is: it stores what it is given per `(source, url)`
 * and hands back what arrived after a position in its log. Every rule that decides who wins lives
 * in SyncMerger, on the client, which is what lets the two sides be written by different people.
 *
 * [now] is that position, read from the server's clock, and becomes the next request's `since` once
 * the changes have been applied -- not before, so a run that fails halfway asks for them again.
 */
@Serializable
data class SyncResponse(
    @ProtoNumber(1) val now: Long,
    @ProtoNumber(2) val changes: List<SyncManga> = emptyList(),
)
