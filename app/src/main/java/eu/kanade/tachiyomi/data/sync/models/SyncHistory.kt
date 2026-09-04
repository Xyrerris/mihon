package eu.kanade.tachiyomi.data.sync.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * One reading session, per chapter, keyed by the chapter's url like [SyncChapter] and like
 * BackupHistory before it.
 *
 * It is a message of its own rather than three more fields on [SyncChapter] because that is the
 * shape the database and the backup already have: history is its own table, a chapter can be marked
 * read without ever having a history row, and a row can be cleared while the chapter stays read.
 * Folding the two together would have to invent an answer for each of those.
 *
 * [lastRead] is epoch milliseconds, and zero means no session rather than one in 1970 -- the same
 * reading the reader gives a history row whose date was cleared. [readDuration] is milliseconds
 * spent on this chapter, which is why it is per chapter: see SyncMerger for what that buys.
 */
@Serializable
data class SyncHistory(
    @ProtoNumber(1) val url: String,
    @ProtoNumber(2) val lastRead: Long = 0,
    @ProtoNumber(3) val readDuration: Long = 0,
)
