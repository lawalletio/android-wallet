package io.lawallet.wallet.app

import io.lawallet.wallet.core.api.ApiException
import io.lawallet.wallet.core.api.Custody
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.nwc.Lncurl
import io.lawallet.wallet.core.nwc.NwcClient
import io.lawallet.wallet.core.nwc.NwcUri
import io.lawallet.wallet.core.plugin.InstalledPlugin
import io.lawallet.wallet.core.plugin.PluginAccount
import io.lawallet.wallet.core.plugin.PluginRegistry
import io.lawallet.wallet.core.store.Prefs
import io.lawallet.wallet.core.wallet.Wallet
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient

/**
 * The account's remote wallets (LaWallet's NWC and LNCurl wallets, plus plugin wallets by id), as every screen reaches
 * them: the list cached under "remote-wallets" so it opens filled offline, the last balances under "rw-balances", and
 * the rules that keep a primary. Screens keep their own optimistic copy of the list and write it back through [cache].
 */
class RemoteWalletRepository(
    private val session: Session,
    private val prefs: Prefs,
    private val plugins: PluginRegistry,
    private val http: OkHttpClient,
    private val wallet: WalletRepository,
) {
    private val api get() = session.api.value

    /** The last list LaWallet gave (null before the first one). */
    val cached: List<RemoteWallet>? get() = prefs.cached(KEY, listSer)
    fun cache(list: List<RemoteWallet>) = prefs.setCached(KEY, listSer, list)

    /** [created] into the cached list (the old primary steps down when it takes over). */
    fun upsert(created: RemoteWallet) {
        val others = cached.orEmpty().filter { it.id != created.id }
        cache(others.map { if (created.isDefault) it.copy(isDefault = false) else it } + created)
    }

    /** Re-reads the list, fixes the primary and caches it; null when LaWallet can't be reached. */
    suspend fun fetch(): List<RemoteWallet>? = runCatching { ensurePrimary(api.remoteWallets()) }.onSuccess(::cache).getOrNull()

    /**
     * There's always a primary while any wallet is active: when it's paused or deleted, the most recently primary
     * wallet that's still active takes over (else the first active one). Returns the list as it now stands.
     */
    suspend fun ensurePrimary(list: List<RemoteWallet>): List<RemoteWallet> {
        list.firstOrNull { it.isDefault && it.isActive }?.let { prefs.notePrimary(it.id); return list }
        val pick = nextPrimary(list, prefs.primaryHistory) ?: return list
        runCatching { api.updateRemoteWallet(pick.id, isDefault = true) }.getOrElse { return list }
        prefs.notePrimary(pick.id)
        runCatching { session.refreshMe() } // the app's wallet connection follows the new primary
        return list.map { it.copy(isDefault = it.id == pick.id) }
    }

    /** Makes [id] the primary: on LaWallet, in the cached list, and the app's wallet connection follows. */
    suspend fun setPrimary(id: String) {
        api.updateRemoteWallet(id, isDefault = true)
        prefs.notePrimary(id)
        cached?.let { list -> cache(list.map { it.copy(isDefault = it.id == id) }) }
        runCatching { session.refreshMe() }
    }

    // ---- balances -----------------------------------------------------------------------------------------------

    /** Last known balance per wallet id. */
    val balances: Map<String, Long> get() = prefs.cached(BALANCES, balancesSer).orEmpty()
    fun saveBalance(id: String, sats: Long) = prefs.setCached(BALANCES, balancesSer, balances + (id to sats))

    /** A live balance, plugin or LaWallet wallet, kept as the last known one. Throws when it can't be read. */
    suspend fun balance(id: String): Long {
        val sats = RemoteWallet.pluginIdOf(id)?.let { (plugins.wallet(it) ?: error("plugin off")).getBalanceMsat() / 1000 } ?: api.remoteWalletBalance(id)
        saveBalance(id, sats)
        return sats
    }

    /** What [id] can spend: live when it answers, else the last known figure; null if unknown. */
    suspend fun spendable(id: String): Long? = runCatching { balance(id) }.getOrNull() ?: balances[id]

    /** A deck wallet by id: a plugin's own wallet (`plugin:<id>`), else a LaWallet wallet's NWC connection. */
    suspend fun walletById(id: String): Wallet =
        RemoteWallet.pluginIdOf(id)?.let { plugins.wallet(it) ?: throw IllegalStateException("${plugins.get(it)?.manifest?.name ?: it} is off") }
            ?: NwcClient(NwcUri.parse(api.remoteWalletConnectionString(id)), http)

    // ---- creating -----------------------------------------------------------------------------------------------

    /**
     * Creates a wallet under [name] or, when the user already has one by that name, the next free "name N": checked
     * against the server's list first, then again on a 409 (another device took it in between). Never fails on the name.
     */
    suspend fun <T> withFreeName(name: String, create: suspend (String) -> T): T {
        val taken = (runCatching { api.remoteWallets() }.getOrNull() ?: cached.orEmpty()).mapTo(mutableSetOf()) { it.name }
        while (true) {
            val free = uniqueName(name, taken)
            try { return create(free) } catch (e: ApiException) { if (e.status != 409 || !taken.add(free)) throw e }
        }
    }

    /**
     * A disposable LNCurl wallet from [provider]. The server's default provider mints server-side; any other is minted
     * here and saved tagged LNCurl with its origin (the server's NWC config takes both), so it shows as LNCurl and can
     * be regenerated like the server's own.
     */
    suspend fun createLncurl(provider: String, name: String?, isDefault: Boolean): RemoteWallet =
        if (provider == Lncurl.DEFAULT) api.createLncurlWallet(name, isDefault)
        else Lncurl.mint(http, provider).let { uri -> withFreeName(name ?: Lncurl.host(provider)) { api.createNwcWallet(it, uri, "SEND_RECEIVE", isDefault, lncurlServerUrl = provider) } }

    /**
     * Replaces a dead LNCurl wallet: a fresh one from the same provider, under the same name, with every address and
     * card that used the old one moved over (and primary if it was), then the old one removed. Nothing changes until
     * the new wallet exists; the old balance is gone with the provider's wallet. Returns the new wallet's id.
     */
    suspend fun regenerateLncurl(old: RemoteWallet): String {
        val api = api
        val origin = old.lncurlServerUrl ?: error("not an LNCurl wallet")
        // Under a stand-in name first: the dead wallet still holds its own (names are unique per account).
        val uri = Lncurl.mint(http, origin.trimEnd('/'))
        val created = withFreeName("${old.name} (new)") { api.createNwcWallet(it, uri, "SEND_RECEIVE", isDefault = false, lncurlServerUrl = origin) }
        wallet.refreshAddresses()
        wallet.addresses.value.filter { it.remoteWalletId == old.id && it.mode == "CUSTOM_NWC" }.forEach { api.routeAddressToWallet(it.username, created.id) }
        api.cards().filter { it.remoteWalletId == old.id }.forEach { api.bindCardToWallet(it.id, created.id) }
        if (old.isDefault) api.updateRemoteWallet(created.id, isDefault = true)
        // Disable, then remove for good; a tombstone the server must keep (audit records) gives up the name instead.
        runCatching { api.deleteRemoteWallet(old.id) }
        if (runCatching { api.deleteRemoteWallet(old.id, permanent = true) }.isFailure) runCatching { api.updateRemoteWallet(old.id, name = "${old.name} (dead)") }
        runCatching { api.updateRemoteWallet(created.id, name = old.name) }
        wallet.refreshAddresses()
        runCatching { session.refreshMe() }
        return created.id
    }

    private companion object {
        const val KEY = "remote-wallets"
        const val BALANCES = "rw-balances"
        val listSer = ListSerializer(RemoteWallet.serializer())
        val balancesSer = MapSerializer(String.serializer(), Long.serializer())
    }
}

/** Who takes over from a primary that's gone: the latest past primary still active, else the first active wallet. */
internal fun nextPrimary(list: List<RemoteWallet>, history: List<String>): RemoteWallet? {
    val active = list.filter { it.isActive }
    return history.firstNotNullOfOrNull { id -> active.find { it.id == id } } ?: active.firstOrNull()
}

/** A new wallet is primary when no active one is (the primary address has no wallet otherwise). */
internal fun needsPrimary(list: List<RemoteWallet>?) = list.orEmpty().none { it.isDefault && it.isActive }

/** "marce" → "marce 2", "marce 3"…: the first not in [taken]. The server's own rule for LNCurl wallets (names are unique per user). */
internal fun uniqueName(requested: String, taken: Set<String>): String {
    if (requested !in taken) return requested
    var n = 2
    while ("$requested $n" in taken) n++
    return "$requested $n"
}

/**
 * The server's wallets plus the enabled wallet plugins that aren't signed out (unknown counts as in: a plugin without
 * `account()`). A plugin wallet is local: it's listed, never the primary (LaWallet can't route an address to it).
 */
internal fun withPlugins(server: List<RemoteWallet>?, plugins: List<InstalledPlugin>, accounts: Map<String, PluginAccount> = emptyMap()): List<RemoteWallet>? {
    val mine = plugins.filter { it.enabled && it.manifest.isWallet && accounts[it.id]?.signedIn != false }.map {
        // Its session never leaves the phone (the plugin's own secrets): local custody.
        RemoteWallet(RemoteWallet.PLUGIN_PREFIX + it.id, it.manifest.name, type = RemoteWallet.PLUGIN, provider = it.id, custodyName = Custody.LOCAL.name)
    }
    if (mine.isEmpty()) return server
    return server.orEmpty() + mine
}
