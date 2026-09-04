package eu.kanade.tachiyomi.data.sync.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * What a device sends to `POST {serverUrl}/api/v1/sync`.
 *
 * [changes] is a delta and not a snapshot: the entries whose local last_modified_at is past the
 * watermark's pushedThrough. A first sync, or one against a server the device has not talked to
 * before, sends everything, because the watermark is then zero.
 *
 * [since] is a position in the server's log, not in this device's clock, and the server is the only
 * one that ever produces the value -- see SyncWatermark. Zero asks for everything the server holds.
 *
 * [deviceId] exists so the server can leave a device's own writes out of what it returns. It is not
 * an identity or a credential; the api key is.
 */
@Serializable
data class SyncRequest(
    @ProtoNumber(1) val deviceId: String,
    @ProtoNumber(2) val since: Long = 0,
    @ProtoNumber(3) val changes: List<SyncManga> = emptyList(),
)
