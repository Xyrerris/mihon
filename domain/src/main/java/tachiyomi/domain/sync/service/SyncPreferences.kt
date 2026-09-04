package tachiyomi.domain.sync.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.sync.model.SyncWatermark
import java.net.URI
import java.util.UUID

/**
 * What the user has decided about syncing reading progress with a server of their own, plus the
 * little state a sync has to remember between runs.
 *
 * Nothing drives a sync yet. The user-facing settings ship ahead of the job so their naming and
 * defaults settle before a protocol depends on them; the state below ships with the protocol it
 * belongs to, because a watermark whose meaning is decided later is a watermark that will be read
 * wrongly once.
 */
@Inject
@SingleIn(AppScope::class)
class SyncPreferences(
    preferenceStore: PreferenceStore,
) {

    val syncEnabled: Preference<Boolean> = preferenceStore.getBoolean("sync_enabled", false)

    val serverUrl: Preference<String> = preferenceStore.getString("sync_server_url", "")

    /**
     * Under a private key, which is what keeps it out of a backup: PreferenceBackupCreator drops
     * every key with that prefix unless the user explicitly asks for private settings, and a
     * .tachibk is a file people hand around.
     */
    val apiKey: Preference<String> = preferenceStore.getString(Preference.privateKey("sync_api_key"), "")

    /** Hours between automatic syncs, or [SYNC_MANUAL] for none. */
    val syncInterval: Preference<Int> = preferenceStore.getInt("sync_interval", SYNC_MANUAL)

    val syncContent: Preference<Set<String>> = preferenceStore.getStringSet(
        "sync_content",
        setOf(CONTENT_PROGRESS),
    )

    val syncOnlyOverWifi: Preference<Boolean> = preferenceStore.getBoolean("sync_only_over_wifi", true)

    /**
     * Which server the watermark below belongs to, and the two halves of the watermark itself.
     *
     * All three are app state rather than settings: they are not answers to a question the user was
     * asked, they mean nothing on another device, and carrying them into a restored backup would
     * tell the receiving device it had already sent changes it has never seen.
     */
    private val watermarkServerUrl = preferenceStore.getString(
        Preference.appStateKey("sync_watermark_server_url"),
        "",
    )

    private val watermarkSince = preferenceStore.getLong(Preference.appStateKey("sync_watermark_since"), 0L)

    private val watermarkPushedThrough = preferenceStore.getLong(
        Preference.appStateKey("sync_watermark_pushed_through"),
        0L,
    )

    private val deviceId = preferenceStore.getString(Preference.appStateKey("sync_device_id"), "")

    /**
     * Where the next sync starts from, or [SyncWatermark.NONE] when there is nothing to start from.
     *
     * A watermark is a position in one server's log and a claim about what that server has already
     * been told. Pointed at a different address it is neither, so it is not carried over: the next
     * run asks for everything and sends everything, which is the only safe reading of "this server
     * has never heard from me". Comparing the addresses here rather than clearing on every edit
     * also means a typo corrected back to the original address costs nothing.
     */
    fun watermark(): SyncWatermark {
        if (watermarkServerUrl.get() != serverUrl.get().trim()) return SyncWatermark.NONE
        return SyncWatermark(
            since = watermarkSince.get(),
            pushedThrough = watermarkPushedThrough.get(),
        )
    }

    /**
     * Records a run that finished. Called after the incoming changes are applied and not before, so
     * a run that dies halfway asks for them again rather than skipping them for good.
     */
    fun recordSync(watermark: SyncWatermark) {
        watermarkSince.set(watermark.since)
        watermarkPushedThrough.set(watermark.pushedThrough)
        watermarkServerUrl.set(serverUrl.get().trim())
    }

    /**
     * This device's name on the wire, minted the first time it is asked for.
     *
     * The server uses it to keep a device's own writes out of what it sends back. It is not a
     * credential -- the api key is -- and it says nothing about the device beyond "the same one as
     * last time", which is the whole of what a sync needs to know.
     */
    fun deviceId(): String {
        return deviceId.get().ifEmpty {
            UUID.randomUUID().toString().also(deviceId::set)
        }
    }

    companion object {
        const val SYNC_MANUAL = 0

        const val CONTENT_PROGRESS = "progress"
        const val CONTENT_CATEGORIES = "categories"
        const val CONTENT_TRACKING = "tracking"

        /**
         * The server has to be reachable over https and nothing else. The address carries an API
         * key on every request and a reading history in both directions, so plain http would hand
         * both to anything on the way; a self-hosted server is not a reason to accept that, and
         * refusing it at the point where the address is typed is cheaper than explaining it later.
         *
         * An address that is merely unset is not valid either: this answers "can a sync run against
         * this", not "has the user finished typing".
         */
        fun isValidServerUrl(url: String): Boolean {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            return uri.scheme == "https" && !uri.host.isNullOrBlank()
        }
    }
}
