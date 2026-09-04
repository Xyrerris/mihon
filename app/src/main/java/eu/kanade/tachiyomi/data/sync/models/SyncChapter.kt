package eu.kanade.tachiyomi.data.sync.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * One chapter's reading state, addressed the way a chapter is addressed across devices.
 *
 * The url is the key. Two devices assign different `chapters._id` to the same chapter, so the local
 * row id cannot travel; the url is what the restore already matches on, and what the sync matches
 * on for the same reason.
 *
 * Only the fields a merge decides are here. Name, scanlator, chapter number, upload and fetch dates
 * are what the source told the device, and the other device has the same source: sending them would
 * be sending a device something it can fetch, and it would let a stale copy overwrite a fresh one.
 * The counts and percentages of manga_progress are missing for the same reason -- they are that
 * device's own arithmetic over these fields.
 *
 * [version] is the counter the chapters trigger bumps whenever read, bookmark or last_page_read
 * change. It is not a merge input in its own right; it is what decides [bookmark], the one field
 * that is not monotonic.
 */
@Serializable
data class SyncChapter(
    @ProtoNumber(1) val url: String,
    @ProtoNumber(2) val read: Boolean = false,
    @ProtoNumber(3) val lastPageRead: Long = 0,
    @ProtoNumber(4) val bookmark: Boolean = false,
    @ProtoNumber(5) val version: Long = 0,
)
