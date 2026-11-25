package io.lawallet.wallet.core.btc

import com.sparrowwallet.hummingbird.ResultType
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoOutput
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import com.sparrowwallet.hummingbird.registry.CryptoSeed
import com.sparrowwallet.hummingbird.registry.ScriptExpression
import com.sparrowwallet.hummingbird.registry.URAccountDescriptor
import com.sparrowwallet.hummingbird.registry.UROutputDescriptor
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * What a keystore export holds, as onchain import needs it: single-sig [accounts] by script type (multipath
 * descriptors), a [multisig] vault's descriptor, and/or this signer's BIP-48 [cosigner] key. [source] names the format.
 */
data class KeystoreExport(val source: String, val accounts: Map<ScriptType, String> = emptyMap(), val multisig: String? = null, val cosigner: String? = null) {
    val chain: Chain get() = Regex("[xtyzuvYZUV]pub[1-9A-HJ-NP-Za-km-z]{100,}").find(listOfNotNull(multisig, cosigner).plus(accounts.values).joinToString(" "))
        ?.let { BtcImport.extendedKey(it.value)?.chain } ?: Chain.MAINNET
}

/*
 * The keystores Sparrow imports from, minus USB (no HWI on a phone): files and QR codes from Coldcard (generic JSON and
 * multisig setup), Electrum wallet files, Sparrow/Specter/Nunchuk wallet JSON, Bitcoin Core `listdescriptors`,
 * Wasabi/Keystone/Cobo JSON, BSMS (BIP-129), a key with its origin (SeedSigner, Jade, Krux, Specter DIY), and BC-UR
 * accounts, keys and descriptors (Keystone, Passport, Jade, Foundation, SeedSigner). Everything becomes descriptors.
 */
object Keystores {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val KEY = "[xtyzuvYZUV]pub[1-9A-HJ-NP-Za-km-z]{100,}"

    /** The export in [raw], or null when it isn't one (a plain xpub, descriptor, address or phrase goes the usual way). */
    fun parse(raw: String): KeystoreExport? {
        val s = raw.trim()
        if (s.startsWith("{")) return runCatching { fromJson(json.parseToJsonElement(s) as JsonObject) }.getOrNull()
        if (s.startsWith("BSMS", ignoreCase = true)) return bsms(s)
        if (Regex("(?im)^\\s*(policy|format)\\s*:").containsMatchIn(s)) return multisigText(s)
        return withOrigin(s)
    }

    // ---- JSON -------------------------------------------------------------------------------------------------

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject

    private fun fromJson(o: JsonObject): KeystoreExport? {
        // Ours (from a BC-UR account) and Bitcoin Core's listdescriptors: a list of descriptors.
        (o["descriptors"] as? JsonArray)?.let { list ->
            val descs = list.mapNotNull { e -> (e as? JsonObject)?.takeIf { it.str("internal") != "true" }?.str("desc") ?: (e as? JsonPrimitive)?.contentOrNull }
                .filter { "prv" !in it }
            return export(o.str("source") ?: "Bitcoin Core", descs, o.str("cosigner"))
        }
        // Sparrow, Specter, Nunchuk wallet files.
        o.str("descriptor")?.let { return export(o.str("source") ?: "Wallet file", listOf(it), null) }
        // Coldcard (and Passport/Keystone "generic JSON"): one block per standard account.
        if (o.obj("bip84") != null || o.obj("bip44") != null || o.obj("bip48_2") != null) return coldcard(o)
        // Electrum wallet files: one keystore, or x1/, x2/… for multisig.
        if (o.obj("keystore") != null || o.obj("x1/") != null) return electrum(o)
        // Wasabi, Keystone/Cobo single-sig JSON.
        o.str("ExtPubKey")?.let { key ->
            val fp = o.str("MasterFingerprint")?.lowercase()
            val path = o.str("AccountKeyPath")?.let { "/" + it.removePrefix("m/").removePrefix("/") }
            return single("Wasabi", key, fp, path)
        }
        return null
    }

    private fun coldcard(o: JsonObject): KeystoreExport? {
        val xfp = o.str("xfp")?.lowercase()
        val accounts = mutableMapOf<ScriptType, String>()
        for ((name, script) in listOf("bip84" to ScriptType.P2WPKH, "bip86" to ScriptType.P2TR, "bip49" to ScriptType.P2SH_P2WPKH, "bip44" to ScriptType.P2PKH)) {
            val b = o.obj(name) ?: continue
            val desc = b.str("desc")?.substringBefore('#')
                ?: keyOf(b.str("xpub") ?: continue, b.str("xfp")?.lowercase() ?: xfp, b.str("deriv"))?.let { wrap(script, "$it/<0;1>/*") }
            if (desc != null) accounts[script] = desc
        }
        val cosigner = o.obj("bip48_2")?.let { b -> keyOf(b.str("xpub") ?: return@let null, b.str("xfp")?.lowercase() ?: xfp, b.str("deriv")) }
        return KeystoreExport("Coldcard", accounts, cosigner = cosigner).takeIf { it.accounts.isNotEmpty() || it.cosigner != null }
    }

    private fun electrum(o: JsonObject): KeystoreExport? {
        // The fingerprint as hex, or Coldcard's little-endian integer.
        fun fp(k: JsonObject) = k.str("root_fingerprint")?.lowercase()
            ?: (k["ckcc_xfp"] as? JsonPrimitive)?.longOrNull?.let { x -> (0..3).joinToString("") { "%02x".format((x ushr (8 * it)) and 0xff) } }
        fun ks(k: JsonObject): String? = keyOf(k.str("xpub") ?: return null, fp(k), k.str("derivation"))
        val type = o.str("wallet_type").orEmpty()
        val m = Regex("^(\\d+)of(\\d+)$").find(type)
        if (m != null) {
            val keys = (1..m.groupValues[2].toInt()).map { ks(o.obj("x$it/") ?: return null) ?: return null }
            // The script is in the keys' SLIP-132 prefix (Zpub/Vpub: P2WSH, Ypub/Upub: P2SH-P2WSH, xpub: P2SH).
            val wrapper = when (o.obj("x1/")?.str("xpub")?.take(1)) { "Z", "V" -> "wsh"; "Y", "U" -> "sh-wsh"; else -> "sh" }
            return KeystoreExport("Electrum", multisig = multi(wrapper, m.groupValues[1].toInt(), keys))
        }
        val k = o.obj("keystore") ?: return null
        return single("Electrum", k.str("xpub") ?: return null, fp(k), k.str("derivation"))
    }

    // ---- text -------------------------------------------------------------------------------------------------

    /** BIP-129: `BSMS 1.0`, the descriptor (a double-star wildcard for both chains), the path restrictions, the first address. */
    private fun bsms(s: String): KeystoreExport? {
        val desc = s.lines().map { it.trim() }.firstOrNull { '(' in it } ?: return null
        return export("BSMS", listOf(desc.replace("/**", "/<0;1>/*")), null)
    }

    /** Coldcard's (and Nunchuk's, Keystone's, BlueWallet's) multisig setup file: Name, Policy, Derivation, Format, `XFP: key`. */
    private fun multisigText(s: String): KeystoreExport? {
        var path: String? = null
        var m = 0
        var format = "P2WSH"
        val keys = mutableListOf<String>()
        for (line in s.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val k = line.substringBefore(':').trim()
            val v = line.substringAfter(':', "").trim()
            when {
                k.equals("policy", true) -> m = Regex("(\\d+)\\s*(of|/)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(v)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                k.equals("derivation", true) -> path = v
                k.equals("format", true) -> format = v.uppercase()
                Regex("^[0-9a-fA-F]{8}$").matches(k) && Regex(KEY).matches(v) -> keys += keyOf(v, k.lowercase(), path) ?: return null
            }
        }
        if (m == 0 || keys.size < 2) return null
        val wrapper = when (format) { "P2SH" -> "sh"; "P2SH-P2WSH", "P2WSH-P2SH" -> "sh-wsh"; else -> "wsh" }
        return KeystoreExport("Multisig file", multisig = multi(wrapper, m, keys))
    }

    /** `[fingerprint/84'/0'/0']zpub…`: a single-sig account by its path's purpose, or a BIP-48 cosigner key. */
    private fun withOrigin(s: String): KeystoreExport? {
        val m = Regex("^\\[([0-9a-fA-F]{8})((?:/\\d+['hH]?)*)]($KEY)(/.*)?$").find(s.replace(Regex("\\s"), "")) ?: return null
        val path = m.groupValues[2]
        return if (path.startsWith("/48")) KeystoreExport("Key with origin", cosigner = keyOf(m.groupValues[3], m.groupValues[1].lowercase(), path))
        else single("Key with origin", m.groupValues[3], m.groupValues[1].lowercase(), path)
    }

    // ---- building descriptors ---------------------------------------------------------------------------------

    /** `[fp/path]xpub`, the key normalised to xpub/tpub; no origin when the fingerprint or path is missing. */
    private fun keyOf(key: String, fp: String?, path: String?): String? {
        val k = BtcImport.extendedKey(key)?.takeIf { !it.private } ?: return null
        val p = path?.trim()?.removePrefix("m")?.replace('h', '\'')?.replace('H', '\'')?.let { if (it.isEmpty() || it.startsWith("/")) it else "/$it" }
        return if (fp != null && p != null) "[$fp$p]${k.xpub}" else k.xpub
    }

    private fun wrap(script: ScriptType, key: String) = when (script) {
        ScriptType.P2WPKH -> "wpkh($key)"
        ScriptType.P2TR -> "tr($key)"
        ScriptType.P2SH_P2WPKH -> "sh(wpkh($key))"
        ScriptType.P2PKH -> "pkh($key)"
    }

    private fun multi(wrapper: String, m: Int, keys: List<String>): String {
        val inner = "sortedmulti($m,${keys.joinToString(",") { "$it/<0;1>/*" }})"
        return when (wrapper) { "sh" -> "sh($inner)"; "sh-wsh" -> "sh(wsh($inner))"; else -> "wsh($inner)" }
    }

    /** One account key: its script from the path's purpose (84', 49'…), else from its SLIP-132 prefix (zpub, ypub…). */
    private fun single(source: String, key: String, fp: String?, path: String?): KeystoreExport? {
        val purpose = path?.let { Regex("^/?(\\d+)").find(it.removePrefix("m"))?.groupValues?.get(1)?.toIntOrNull() }
        val script = ScriptType.entries.firstOrNull { it.purpose == purpose } ?: BtcImport.extendedKey(key)?.script ?: ScriptType.P2WPKH
        val k = keyOf(key, fp, path) ?: return null
        return KeystoreExport(source, mapOf(script to wrap(script, "$k/<0;1>/*")))
    }

    /** Descriptors sorted by kind: single-sig by script, a multisig, a `cosigner(…)` key. */
    private fun export(source: String, descriptors: List<String>, cosigner: String?): KeystoreExport? {
        val accounts = mutableMapOf<ScriptType, String>()
        var multisig: String? = null
        var cos = cosigner
        for (raw in descriptors) {
            val d = raw.trim()
            val body = d.substringBefore('#')
            when {
                "multi(" in body -> multisig = multisig ?: d
                body.startsWith("cosigner(") -> cos = cos ?: body.removePrefix("cosigner(").removeSuffix(")")
                else -> ScriptType.entries.firstOrNull { body.startsWith(wrap(it, "").removeSuffix(")").removeSuffix(")")) }?.let { accounts.putIfAbsent(it, d) }
            }
        }
        return KeystoreExport(source, accounts, multisig, cos).takeIf { it.accounts.isNotEmpty() || it.multisig != null || it.cosigner != null }
    }

    // ---- BC-UR ------------------------------------------------------------------------------------------------

    /**
     * A decoded UR as text the importers read: accounts and descriptors as our `{"descriptors": […]}` JSON, a key as
     * `[fp/path]xpub`, a seed as [SeedQr.BINARY] entropy, a PSBT as base64, plain bytes as UTF-8. Null when unknown.
     */
    fun urText(ur: UR): String? = runCatching {
        when (val item = if (ur.type == "bytes") null else ur.decodeFromRegistry()) {
            null -> ur.toBytes().decodeToString()
            is CryptoAccount -> accountJson(item.outputDescriptors.mapNotNull(::outputText))
            is URAccountDescriptor -> accountJson(item.outputDescriptors.mapNotNull(::outputText))
            is CryptoOutput -> outputText(item)
            is UROutputDescriptor -> outputText(item)
            is CryptoHDKey -> hdKey(item)
            is CryptoSeed -> SeedQr.BINARY + item.seed.joinToString("") { "%02x".format(it) }
            is CryptoPSBT -> Base64.getEncoder().encodeToString(item.psbt)
            else -> null
        }
    }.getOrNull()

    private fun accountJson(descs: List<String>) = buildJsonObject {
        put("source", "BC-UR")
        put("descriptors", buildJsonArray { descs.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    private fun outputText(o: CryptoOutput): String? {
        val inner = o.hdKey?.let { k -> hdKey(k)?.let { it + children(k) } }
            ?: o.multiKey?.let { mk -> mk.hdKeys.map { k -> hdKey(k)?.plus(children(k)) }.takeIf { null !in it }?.let { "${mk.threshold}," + it.joinToString(",") } }
            ?: return null
        return o.scriptExpressions.foldRight(inner) { e, acc -> if (e == ScriptExpression.COSIGNER) "cosigner(${acc.substringBefore("/<")})" else "${e.expression}($acc)" }
    }

    private fun outputText(o: UROutputDescriptor): String? {
        var s = o.source
        o.keys.forEachIndexed { i, k -> s = s.replace("@$i", (k as? CryptoHDKey)?.let { (hdKey(it) ?: return null) + children(it) } ?: return null) }
        return s
    }

    private fun children(k: CryptoHDKey) = k.children?.path?.takeIf { it.isNotEmpty() }?.let { "/$it" } ?: "/<0;1>/*"

    /** An extended public key with its origin, rebuilt from its parts (version from the path's coin type: 1' is test). */
    internal fun hdKey(k: CryptoHDKey): String? {
        if (k.isPrivateKey) return null
        val key = k.key ?: return null
        val chainCode = k.chainCode ?: return null
        val comps = k.origin?.components.orEmpty().filterIsInstance<IndexPathComponent>()
        val version = if (comps.getOrNull(1)?.index == 1) 0x043587CF else 0x0488B21E
        val child = comps.lastOrNull()?.let { it.index.toLong() or (if (it.isHardened) 0x80000000L else 0L) } ?: 0L
        val payload = byteArrayOf((version ushr 24).toByte(), (version ushr 16).toByte(), (version ushr 8).toByte(), version.toByte()) +
            byteArrayOf((k.origin?.depth ?: comps.size).toByte()) + (k.parentFingerprint ?: ByteArray(4)) +
            byteArrayOf((child ushr 24).toByte(), (child ushr 16).toByte(), (child ushr 8).toByte(), child.toByte()) + chainCode + key
        val xpub = Base58.encodeCheck(payload)
        val fp = k.origin?.sourceFingerprint?.joinToString("") { "%02x".format(it) } ?: return xpub
        return "[$fp${comps.joinToString("") { "/${it.index}" + if (it.isHardened) "'" else "" }}]$xpub"
    }
}

/** Collects animated BC-UR frames (any type: accounts, keys, descriptors, PSBTs, seeds, bytes) into [Keystores.urText]. */
class UrAssembler {
    private var decoder = URDecoder()

    /** 0..1 while collecting; the text once complete (then resets). A failed or unknown UR gives back null. */
    fun receive(part: String): Pair<Double, String?> {
        decoder.receivePart(part.trim().lowercase())
        val r = decoder.result ?: return decoder.estimatedPercentComplete to null
        decoder = URDecoder()
        return if (r.type == ResultType.SUCCESS) 1.0 to Keystores.urText(r.ur) else 0.0 to null
    }

    companion object { fun isUr(text: String) = text.trim().startsWith("ur:", ignoreCase = true) }
}
