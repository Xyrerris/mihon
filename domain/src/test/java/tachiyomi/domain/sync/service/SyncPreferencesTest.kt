package tachiyomi.domain.sync.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SyncPreferencesTest {

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
}
