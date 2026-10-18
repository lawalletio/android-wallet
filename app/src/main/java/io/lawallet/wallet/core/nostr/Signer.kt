package io.lawallet.wallet.core.nostr

/** Anything that can sign events for the user's Nostr identity. */
interface Signer {
    val pubkey: String
    suspend fun sign(kind: Int, content: String, tags: List<List<String>>, createdAt: Long = NostrEvent.nowSeconds()): NostrEvent
}

class LocalSigner(val keys: Keys) : Signer {
    override val pubkey get() = keys.pubkey
    override suspend fun sign(kind: Int, content: String, tags: List<List<String>>, createdAt: Long) =
        NostrEvent.sign(keys, kind, content, tags, createdAt)
}

suspend fun Signer.nip98Header(url: String, method: String, body: ByteArray? = null): String =
    Nip98.header(sign(Nip98.KIND, "", Nip98.tags(url, method, body)))
