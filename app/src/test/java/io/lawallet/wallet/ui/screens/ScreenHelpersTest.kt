package io.lawallet.wallet.ui.screens

import io.lawallet.wallet.app.domainAvatar
import io.lawallet.wallet.core.api.Poll
import io.lawallet.wallet.core.api.PollOption
import io.lawallet.wallet.core.api.PollResponse
import io.lawallet.wallet.core.api.PollState
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.api.WalletAddress
import io.lawallet.wallet.core.auth.PasskeyException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Pure helpers behind screens: what they decide, without Compose. */
class ScreenHelpersTest {
    @Test fun domainAvatarMapsLawalletIoToItsArLogo() {
        assertEquals("https://raw.githubusercontent.com/lawalletio/static/main/public/img/domains/lawallet.ar.png", domainAvatar("lawallet.io"))
        assertEquals("https://raw.githubusercontent.com/lawalletio/static/main/public/img/domains/getalby.com.png", domainAvatar("getalby.com"))
    }

    @Test fun absoluteJoinsRelativePathsOnly() {
        assertEquals("https://x.io/card.png", absolute("https://x.io/", "/card.png"))
        assertEquals("https://x.io/card.png", absolute("https://x.io", "card.png"))
        assertEquals("https://cdn.io/a.png", absolute("https://x.io", "https://cdn.io/a.png"))
    }

    @Test fun passkeyErrorPicksTheCopyByKind() {
        assertEquals("C", passkeyError(PasskeyException("cancelled", "raw"), "C", "P"))
        assertEquals("P", passkeyError(PasskeyException("prf-unsupported", "raw"), "C", "P"))
        assertEquals("raw", passkeyError(PasskeyException("other", "raw"), "C", "P"))
        assertEquals("boom", passkeyError(IllegalStateException("boom"), "C", "P"))
        assertEquals("Passkey error", passkeyError(IllegalStateException(), "C", "P"))
    }

    @Test fun addressKindTrustsRoutingUntilWalletsLoad() {
        val nwc = WalletAddress("a", mode = "CUSTOM_NWC", remoteWalletId = "w1")
        val wallets = listOf(RemoteWallet("w1", "Alby"), RemoteWallet("w2", "LNCurl", provider = "lncurl"), RemoteWallet("w3", "Old", status = "DISABLED"))
        assertEquals(AddressKind.NWC, addressKind(nwc, null))
        assertEquals(AddressKind.NWC, addressKind(nwc, wallets))
        assertEquals(AddressKind.LNCURL, addressKind(nwc.copy(remoteWalletId = "w2"), wallets))
        assertEquals(AddressKind.IDLE, addressKind(nwc.copy(remoteWalletId = "w3"), wallets)) // archived: unlinked
        assertEquals(AddressKind.IDLE, addressKind(nwc.copy(remoteWalletId = "gone"), wallets))
        assertEquals(AddressKind.FORWARD, addressKind(WalletAddress("a", mode = "ALIAS"), wallets))
        assertEquals(AddressKind.FORWARD, addressKind(WalletAddress("a", mode = "PROXY_ALIAS"), null))
        assertEquals(AddressKind.IDLE, addressKind(WalletAddress("a"), wallets))
    }

    @Test fun cashOutFigures() {
        val was = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("20,001", ars(20_000.5))
            assertEquals("1.06", arsPerSat(106_000_000.0))
            assertEquals("1.1219", arsPerSat(112_190_000.0))
            assertEquals("1,234.50", arsPerSat(123_450_000_000.0))
        } finally { Locale.setDefault(was) }
    }

    private val options = listOf(PollOption("tron", "Tron"), PollOption("liquid", "Liquid"), PollOption("eth", "Ethereum"))
    private fun state(type: String, choices: List<String>?) = PollState(Poll(type = type, options = options), choices?.let { PollResponse(it) })

    @Test fun pollInitialChoices() {
        assertEquals(listOf("tron", "liquid", "eth"), initialChoices(state("RANKING", null)))
        // The stored ranking, without options the poll no longer has; what it left out stays out.
        assertEquals(listOf("liquid", "custom:Base", "tron"), initialChoices(state("RANKING", listOf("liquid", "gone", "custom:Base", "tron"))))
        // Single choice: every option, plus the one custom entry given.
        assertEquals(listOf("tron", "liquid", "eth", "custom:Base"), initialChoices(state("SINGLE_CHOICE", listOf("custom:Base", "custom:Other"))))
        assertEquals(emptyList<String>(), initialChoices(state("TEXT", listOf("custom:hi"))))
    }

    // regression: dcfb2bf fix(home): mark no wallet when the selected address routes to an archived one
    @Test fun regression_dcfb2bf_markShownMarksNoneWhenThePickedAddressWalletIsArchived() {
        val ws = listOf(RemoteWallet("p", "Primary", isDefault = true), RemoteWallet("a", "Other"), RemoteWallet("plugin:x", "X", type = RemoteWallet.PLUGIN))
        assertEquals(ws, markShown(ws, picked = false, shown = null)) // no pick: the account's own primary
        assertEquals(listOf("a"), markShown(ws, picked = true, shown = "a").filter { it.isDefault }.map { it.id })
        val archived = markShown(ws, picked = true, shown = "archived")
        assertEquals(emptyList<String>(), archived.filter { it.isDefault }.map { it.id })
        assertEquals(listOf("p", "a"), archived.map { it.id }) // plugins can't back an address
    }
}
