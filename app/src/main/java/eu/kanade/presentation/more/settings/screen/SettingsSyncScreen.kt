package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import mihon.app.di.appGraph
import tachiyomi.domain.sync.service.SyncPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Settings for syncing reading progress with a server the user runs.
 *
 * Everything here writes a preference and nothing else reads it yet, which is the point of shipping
 * the screen first: the words, the grouping and the defaults are what a later phase has to build
 * against, and they are cheaper to argue about now than after a protocol depends on them.
 */
object SettingsSyncScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_sync

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val syncPreferences = remember { context.appGraph.syncPreferences }
        val syncEnabled by syncPreferences.syncEnabled.collectAsState()

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = syncPreferences.syncEnabled,
                title = stringResource(MR.strings.pref_sync_enabled),
                subtitle = stringResource(MR.strings.pref_sync_enabled_summary),
            ),
            getServerGroup(syncPreferences = syncPreferences, enabled = syncEnabled),
            getSyncingGroup(syncPreferences = syncPreferences, enabled = syncEnabled),
            // Removed by the phase that gives the switch something to do. Until then the screen
            // stores answers to questions nothing asks, and saying so is better than letting
            // someone turn it on and wait for a sync that is never coming.
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_sync_unimplemented_info)),
        )
    }

    @Composable
    private fun getServerGroup(
        syncPreferences: SyncPreferences,
        enabled: Boolean,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val apiKey by syncPreferences.apiKey.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_sync_group_server),
            enabled = enabled,
            preferenceItems = listOf(
                Preference.PreferenceItem.EditTextPreference(
                    preference = syncPreferences.serverUrl,
                    title = stringResource(MR.strings.pref_sync_server_url),
                    onValueChanged = {
                        if (!SyncPreferences.isValidServerUrl(it)) {
                            context.toast(MR.strings.pref_sync_server_url_invalid)
                            return@EditTextPreference false
                        }
                        true
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = syncPreferences.apiKey,
                    title = stringResource(MR.strings.pref_sync_api_key),
                    // The key itself stays off the screen: settings are read over shoulders and
                    // shown in screenshots, and this one is a credential.
                    subtitle = stringResource(
                        if (apiKey.isBlank()) {
                            MR.strings.pref_sync_api_key_unset
                        } else {
                            MR.strings.pref_sync_api_key_set
                        },
                    ),
                ),
            ),
        )
    }

    @Composable
    private fun getSyncingGroup(
        syncPreferences: SyncPreferences,
        enabled: Boolean,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_sync_group_syncing),
            enabled = enabled,
            preferenceItems = listOf(
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = syncPreferences.syncContent,
                    entries = mapOf(
                        SyncPreferences.CONTENT_PROGRESS to stringResource(MR.strings.pref_sync_content_progress),
                        SyncPreferences.CONTENT_CATEGORIES to stringResource(MR.strings.categories),
                        SyncPreferences.CONTENT_TRACKING to stringResource(MR.strings.track),
                    ),
                    title = stringResource(MR.strings.pref_sync_content),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = syncPreferences.syncInterval,
                    entries = mapOf(
                        SyncPreferences.SYNC_MANUAL to stringResource(MR.strings.pref_sync_interval_manual),
                        1 to stringResource(MR.strings.update_1hour),
                        6 to stringResource(MR.strings.update_6hour),
                        12 to stringResource(MR.strings.update_12hour),
                        24 to stringResource(MR.strings.update_24hour),
                    ),
                    title = stringResource(MR.strings.pref_sync_interval),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = syncPreferences.syncOnlyOverWifi,
                    title = stringResource(MR.strings.pref_sync_only_over_wifi),
                ),
            ),
        )
    }
}
