package tachiyomi.domain.sync.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.sync.model.SyncWatermark

class SyncPreferencesTest {

    private fun preferences(serverUrl: String = "https://sync.example.com") =
        SyncPreferences(InMemoryPreferenceStore()).also { it.serverUrl.set(serverUrl) }

    @Test
    fun `When the address is https expect it accepted`() {
        SyncPreferences.isValidServerUrl("https://sync.example.com") shouldBe true
        SyncPreferences.isValidServerUrl("https://sync.example.com:8443/api") shouldBe true
        SyncPreferences.isValidServerUrl("  https://sync.example.com  ") shouldBe true
    }

    @Test
    fun `When the address is plain http expect it refused`() {
        SyncPreferences.isValidServerUrl("http://sync.example.com") shouldBe false
    }

    @Test
    fun `When the scheme is missing expect it refused`() {
        SyncPreferences.isValidServerUrl("sync.example.com") shouldBe false
    }

    @Test
    fun `When there is no host expect it refused`() {
        SyncPreferences.isValidServerUrl("https://") shouldBe false
    }

    @Test
    fun `When the address is unset or unparseable expect it refused`() {
        SyncPreferences.isValidServerUrl("") shouldBe false
        SyncPreferences.isValidServerUrl("   ") shouldBe false
        SyncPreferences.isValidServerUrl("https://exa mple.com") shouldBe false
    }

    @Test
    fun `When no sync has run expect nothing to start from`() {
        preferences().watermark() shouldBe SyncWatermark.NONE
    }

    @Test
    fun `When a run is recorded expect the next one to start from it`() {
        val preferences = preferences()

        preferences.recordSync(SyncWatermark(since = 1_700L, pushedThrough = 42L))

        preferences.watermark() shouldBe SyncWatermark(since = 1_700L, pushedThrough = 42L)
    }

    @Test
    fun `When the server changes expect the watermark dropped rather than reused`() {
        val preferences = preferences()
        preferences.recordSync(SyncWatermark(since = 1_700L, pushedThrough = 42L))

        preferences.serverUrl.set("https://other.example.com")

        preferences.watermark() shouldBe SyncWatermark.NONE
    }

    @Test
    fun `When the address comes back expect the watermark back with it`() {
        val preferences = preferences()
        preferences.recordSync(SyncWatermark(since = 1_700L, pushedThrough = 42L))

        preferences.serverUrl.set("https://sync.example.com/")
        preferences.serverUrl.set("  https://sync.example.com  ")

        preferences.watermark() shouldBe SyncWatermark(since = 1_700L, pushedThrough = 42L)
    }

    @Test
    fun `When the device id is asked for twice expect the same one`() {
        val preferences = preferences()

        val first = preferences.deviceId()

        first shouldNotBe ""
        preferences.deviceId() shouldBe first
    }
}
