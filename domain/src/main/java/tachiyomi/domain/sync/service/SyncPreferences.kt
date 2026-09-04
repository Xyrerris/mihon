package tachiyomi.domain.sync.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.net.URI

/**
 * What the user has decided about syncing reading progress with a server of their own.
 *
 * Nothing reads these beyond the settings screen yet, and that is deliberate: the screen ships on
 * its own, with the switch off, so the naming and the shape of the options settle before there is a
 * protocol and a job depending on them.
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
