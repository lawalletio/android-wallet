package io.lawallet.wallet.core

import io.lawallet.wallet.core.api.CurrentUser
import io.lawallet.wallet.core.api.InstanceSettings
import io.lawallet.wallet.core.api.LaWalletApi
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.api.Swap
import io.lawallet.wallet.core.api.SwapQuote
import io.lawallet.wallet.core.api.WalletAddress
import io.lawallet.wallet.core.api.WalletCard
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** API shapes as older servers (fields missing) and newer ones (fields added, nulls) send them. */
class ModelsTest {
    private fun <T> decode(ser: KSerializer<T>, s: String) = LaWalletApi.json.decodeFromString(ser, s)

    @Test fun minimalRemoteWalletGetsTodaysDefaults() {
        val w = decode(RemoteWallet.serializer(), """{"id":"w","name":"n"}""")
        assertEquals("NWC", w.type)
        assertTrue(w.isActive)
        assertFalse(w.isDefault || w.isLncurl || w.isPlugin)
        assertNull(w.receiveCapabilities)
    }

    @Test fun newerRemoteWalletFieldsAndNullsAreTolerated() {
        val w = decode(RemoteWallet.serializer(), """{"id":"w","name":"n","status":null,"isDefault":null,"provider":"lncurl","futureField":{"a":1},"receiveCapabilities":{"nip57":true,"extra":0}}""")
        assertTrue(w.isActive) // null coerced to the default
        assertTrue(w.isLncurl)
        assertEquals(true, w.receiveCapabilities?.lud21)
        assertEquals(true, w.receiveCapabilities?.nip57)
    }

    @Test fun activeAndLncurlFollowStatusAndProvider() {
        assertFalse(RemoteWallet("w", "n", status = "DISABLED").isActive)
        assertFalse(RemoteWallet("w", "n", status = "DEAD").isActive)
        assertFalse(RemoteWallet("w", "n", provider = "alby").isLncurl)
        assertTrue(RemoteWallet("plugin:fiwind", "Fiwind", type = RemoteWallet.PLUGIN).isPlugin)
        assertEquals("fiwind", RemoteWallet.pluginIdOf("plugin:fiwind"))
        assertNull(RemoteWallet.pluginIdOf("w1"))
    }

    @Test fun currentUserNwcPrefersTheEffectiveStringAndSkipsBlanks() {
        assertEquals("e", CurrentUser("u", nwcString = "n", effectiveNwcString = "e").nwc)
        assertEquals("n", CurrentUser("u", nwcString = "n", effectiveNwcString = " ").nwc)
        assertNull(CurrentUser("u", nwcString = "", effectiveNwcString = null).nwc)
        val old = decode(CurrentUser.serializer(), """{"userId":"u","lightningAddress":null,"newThing":[1]}""")
        assertNull(old.nwc)
        assertNull(old.cardRecoveryEnabled)
    }

    @Test fun settingsFlagsAreTheStringTrueOnly() {
        val s = decode(InstanceSettings.serializer(), """{"integration_bullbitcoin":"false","integration_wapu":"true","integration_alias":"TRUE","lncurl_auto_create":"true","new_setting":"x"}""")
        assertTrue(s.swapsEnabled)
        assertFalse(s.bankLookupEnabled)
        assertTrue(s.lncurlAutoCreate)
        assertFalse(s.lncurlEnabled || s.emailLoginEnabled)
        assertFalse(decode(InstanceSettings.serializer(), "{}").swapsEnabled)
    }

    @Test fun addressAndCardDefaults() {
        val a = decode(WalletAddress.serializer(), """{"username":"ana","mode":null}""")
        assertEquals("IDLE", a.mode)
        assertEquals("NONE", a.nwcMode)
        assertFalse(a.isPrimary)
        val c = decode(WalletCard.serializer(), """{"id":"c","design":{"imageUrl":"/x.png"},"ntag424":{"ctr":7}}""")
        assertEquals("SIMPLE", c.kind)
        assertFalse(c.blocked || c.disabled)
        assertEquals(7L, c.ntag424?.ctr)
    }

    @Test fun swapsDecodeFromOlderAndNewerServers() {
        val s = decode(Swap.serializer(), """{"id":"s","provider":"wapu","amountArs":null,"newField":true}""")
        assertEquals("PENDING", s.status)
        assertEquals(0.0, s.amountArs, 0.0)
        assertEquals("", s.bolt11)
        val q = decode(SwapQuote.serializer(), """{"provider":"wapu","error":"down"}""")
        assertNull(q.sats)
        assertEquals("down", q.error)
    }
}
