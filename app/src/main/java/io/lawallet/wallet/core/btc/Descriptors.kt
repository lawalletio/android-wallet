package io.lawallet.wallet.core.btc

import io.lawallet.wallet.core.nostr.sha256
import org.bitcoindevkit.Address
import org.bitcoindevkit.DerivationPath
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import java.math.BigDecimal
import java.net.URLDecoder
import java.net.URLEncoder

/** A wallet's public descriptors (with #checksum): receive (external) and change (internal) chains. */
@kotlinx.serialization.Serializable
data class DescriptorPair(val external: String, val internal: String)

/*
 * Every onchain wallet is a pair of output descriptors; BDK derives addresses and signs from them. Wallets keep only
 * the public pair; a seed wallet's signing descriptors are rebuilt from its phrase for one signature and dropped.
 */
object Descriptors {
    private val KEYCHAINS = listOf(KeychainKind.EXTERNAL, KeychainKind.INTERNAL)

    private fun secretKey(words: List<String>, passphrase: String?, chain: Chain) =
        DescriptorSecretKey(chain.kind, Mnemonic.fromString(words.joinToString(" ")), passphrase?.takeIf { it.isNotEmpty() })

    private fun template(script: ScriptType, key: DescriptorSecretKey, keychain: KeychainKind, chain: Chain) = when (script) {
        ScriptType.P2WPKH -> Descriptor.newBip84(key, keychain, chain.kind)
        ScriptType.P2TR -> Descriptor.newBip86(key, keychain, chain.kind)
        ScriptType.P2SH_P2WPKH -> Descriptor.newBip49(key, keychain, chain.kind)
        ScriptType.P2PKH -> Descriptor.newBip44(key, keychain, chain.kind)
    }

    /** A seed wallet's signing descriptors (both chains). Hold them only while signing. */
    fun secret(words: List<String>, passphrase: String?, script: ScriptType, chain: Chain): List<Descriptor> =
        secretKey(words, passphrase, chain).let { k -> KEYCHAINS.map { template(script, k, it, chain) } }

    fun fromPhrase(words: List<String>, passphrase: String?, script: ScriptType, chain: Chain): DescriptorPair =
        secret(words, passphrase, script, chain).let { DescriptorPair(it[0].asPublic().toString(), it[1].asPublic().toString()) }

    private fun wrap(script: ScriptType, key: String) = when (script) {
        ScriptType.P2WPKH -> "wpkh($key)"
        ScriptType.P2TR -> "tr($key)"
        ScriptType.P2SH_P2WPKH -> "sh(wpkh($key))"
        ScriptType.P2PKH -> "pkh($key)"
    }

    /** Watch-only from an account key (xpub/ypub/zpub/tpub/vpub…), with no origin info (the key doesn't carry it). */
    fun fromXpub(key: String, script: ScriptType, chain: Chain): DescriptorPair {
        val k = requireNotNull(BtcImport.extendedKey(key)?.takeIf { !it.private }) { "not an extended public key" }.xpub
        return DescriptorPair(Descriptor(wrap(script, "$k/0/*"), chain.kind).toString(), Descriptor(wrap(script, "$k/1/*"), chain.kind).toString())
    }

    /**
     * A wallet from an extended private key's signing descriptors (both chains). A master key (depth 0) takes the
     * script's standard account, m/84'/coin'/0' and so on; an account key (what Sparrow and Electrum export) is used
     * as is. Hold them only while signing.
     */
    fun xprvSecret(key: String, script: ScriptType, chain: Chain): List<Descriptor> {
        val k = requireNotNull(BtcImport.extendedKey(key)?.takeIf { it.private && !it.multisig }) { "not an extended private key" }
        val base = if (k.depth == 0) "${k.xpub}/${script.purpose}'/${chain.coin}'/0'" else k.xpub
        return listOf(0, 1).map { Descriptor(wrap(script, "$base/$it/*"), chain.kind) }
    }

    fun fromXprv(key: String, script: ScriptType, chain: Chain): DescriptorPair =
        xprvSecret(key, script, chain).let { DescriptorPair(it[0].asPublic().toString(), it[1].asPublic().toString()) }

    /**
     * Any public descriptor: a multipath `<0;1>` one, or a receive one whose change chain is the same with `/1/…`.
     * SLIP-132 keys (zpub, Zpub…) are rewritten as xpub/tpub first, which voids a #checksum, so it's dropped then.
     */
    fun fromDescriptor(raw: String, chain: Chain): DescriptorPair {
        val s = raw.replace(Regex("\\s"), "")
        val norm = BtcImport.normalizeKeys(s).let { if (it != s) it.substringBefore('#') else it }
        val d = Descriptor(norm, chain.kind)
        if (d.isMultipath()) return d.toSingleDescriptors().let { DescriptorPair(it[0].toString(), it[1].toString()) }
        require(d.hasWildcard()) { "descriptor has no wildcard" }
        val body = d.toString().substringBefore('#')
        val change = body.replace("/0/*", "/1/*")
        require(change != body) { "descriptor has no receive branch" }
        return DescriptorPair(d.toString(), Descriptor(change, chain.kind).toString())
    }

    /** BIP-48 path of a P2WSH multisig cosigner key: m/48'/coin'/0'/2'. */
    fun cosignerPath(chain: Chain) = "m/48'/${chain.coin}'/0'/2'"

    /** This seed's multisig cosigner key with its origin, `[fingerprint/48'/c'/0'/2']xpub…`, what Sparrow and Coldcard export. */
    fun cosigner(words: List<String>, passphrase: String?, chain: Chain): String =
        secretKey(words, passphrase, chain).derive(DerivationPath(cosignerPath(chain))).asPublic().toString()

    /**
     * A cosigner key as typed or scanned: `[fp/path]xpub`, a bare xpub/Zpub/Vpub, maybe with a trailing `/0/…` or
     * `/<0;1>/…`. Returns it normalised to `[fp/path]xpub` (or a bare xpub), or null when it isn't one.
     */
    fun cosignerKey(raw: String): Pair<String, Chain>? {
        val s = raw.trim().replace(Regex("\\s"), "").substringBefore("/<").removeSuffix("/*").removeSuffix("/0").removeSuffix("/1")
        val m = Regex("^(\\[[0-9a-fA-F]{8}(/[0-9]+['hH]?)*])?([1-9A-HJ-NP-Za-km-z]{100,})$").find(s) ?: return null
        val k = BtcImport.extendedKey(m.groupValues[3])?.takeIf { !it.private } ?: return null
        return (m.groupValues[1] + k.xpub) to k.chain
    }

    /** An m-of-n P2WSH `sortedmulti` vault over [keys] (each `[fp/path]xpub` or a bare xpub). */
    fun multisig(threshold: Int, keys: List<String>, chain: Chain): DescriptorPair {
        require(keys.size in 2..15 && threshold in 1..keys.size) { "bad quorum" }
        fun d(branch: Int) = "wsh(sortedmulti($threshold,${keys.joinToString(",") { "$it/$branch/*" }}))"
        return DescriptorPair(Descriptor(d(0), chain.kind).toString(), Descriptor(d(1), chain.kind).toString())
    }

    /** m and n of a multisig descriptor, or null for single-sig. */
    fun quorum(descriptor: String): Pair<Int, Int>? {
        val m = Regex("multi\\((\\d+),").find(descriptor) ?: return null
        return m.groupValues[1].toInt() to Regex("[xt]pub[1-9A-HJ-NP-Za-km-z]{100,}").findAll(descriptor).count()
    }

    /** The vault's signing descriptors with this seed's key in it, or null when the seed isn't one of its cosigners. */
    fun multisigSecret(pair: DescriptorPair, words: List<String>, passphrase: String?, chain: Chain): List<Descriptor>? {
        val sk = secretKey(words, passphrase, chain).derive(DerivationPath(cosignerPath(chain)))
        val xpub = sk.asPublic().toString().substringAfter(']')
        if (xpub !in pair.external) return null
        val xprv = sk.toString().substringAfter(']')
        return listOf(pair.external, pair.internal).map { Descriptor(it.substringBefore('#').replace(xpub, xprv), chain.kind) }
    }

    fun address(descriptor: String, index: Int, chain: Chain): String = Descriptor(descriptor, chain.kind).deriveAddress(index.toUInt(), chain.bdk).toString()

    /** Which chain [address] belongs to, or null if it isn't a valid address. */
    fun chainOf(address: String): Chain? = Chain.entries.firstOrNull { c -> runCatching { Address(address, c.bdk) }.isSuccess }

    /** Electrum's id for an output script: sha256 of the script, byte-reversed, hex. */
    fun scripthash(script: ByteArray): String = sha256(script).reversedArray().joinToString("") { "%02x".format(it) }
}

/** A BIP-21 payment request: `bitcoin:<address>?amount=<btc>&label=…&message=…&lightning=…&pj=…` ([pj]: a payjoin endpoint). */
data class Bip21(val address: String, val sats: Long? = null, val label: String? = null, val message: String? = null, val lightning: String? = null, val pj: String? = null) {
    /** As a URI. Bech32 addresses are upper-cased when [qr] (QR alphanumeric mode: a smaller code). */
    fun uri(qr: Boolean = false): String {
        val a = if (qr && (address.startsWith("bc1", true) || address.startsWith("tb1", true))) address.uppercase() else address
        val q = listOfNotNull(
            sats?.let { "amount=" + BigDecimal.valueOf(it, 8).stripTrailingZeros().toPlainString() },
            label?.takeIf { it.isNotBlank() }?.let { "label=" + enc(it) },
            message?.takeIf { it.isNotBlank() }?.let { "message=" + enc(it) },
        )
        return (if (qr) "BITCOIN:" else "bitcoin:") + a + if (q.isEmpty()) "" else "?" + q.joinToString("&")
    }

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** A `bitcoin:` URI or a bare address (validity isn't checked here), or null. */
        fun parse(raw: String): Bip21? {
            val s = raw.trim()
            val body = if (s.startsWith("bitcoin:", ignoreCase = true)) s.substring(8) else if (':' in s || s.isEmpty()) return null else s
            val address = body.substringBefore('?').takeIf { it.isNotEmpty() } ?: return null
            val params = body.substringAfter('?', "").split('&').filter { '=' in it }
                // A `+` stays a `+` (not a space): BIP-77's `pj` fragment used it as a separator.
                .associate { it.substringBefore('=').lowercase() to URLDecoder.decode(it.substringAfter('=').replace("+", "%2B"), "UTF-8") }
            // An unknown required parameter (BIP-21 `req-`) means we can't honour the request.
            if (params.keys.any { it.startsWith("req-") }) return null
            val sats = params["amount"]?.let { runCatching { BigDecimal(it).movePointRight(8).longValueExact() }.getOrNull() ?: return null }
            return Bip21(address.let { if (it.startsWith("BC1") || it.startsWith("TB1")) it.lowercase() else it }, sats, params["label"], params["message"], params["lightning"], params["pj"])
        }
    }
}
