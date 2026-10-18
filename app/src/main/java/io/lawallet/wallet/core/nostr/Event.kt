package io.lawallet.wallet.core.nostr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class NostrEvent(
    val id: String,
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun tag(name: String): String? = tags.firstOrNull { it.size > 1 && it[0] == name }?.get(1)

    fun verify(): Boolean = runCatching {
        computeId(pubkey, createdAt, kind, tags, content) == id &&
            Keys.verify(sig.hexToBytes(), id.hexToBytes(), pubkey)
    }.getOrDefault(false)

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun sign(keys: Keys, kind: Int, content: String, tags: List<List<String>> = emptyList(), createdAt: Long = nowSeconds()): NostrEvent {
            val id = computeId(keys.pubkey, createdAt, kind, tags, content)
            return NostrEvent(id, keys.pubkey, createdAt, kind, tags, content, keys.sign(id.hexToBytes()).toHex())
        }

        fun computeId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
            val sb = StringBuilder("[0,")
            sb.jsonString(pubkey).append(',').append(createdAt).append(',').append(kind).append(",[")
            tags.forEachIndexed { i, tag ->
                if (i > 0) sb.append(',')
                sb.append('[')
                tag.forEachIndexed { j, v -> if (j > 0) sb.append(','); sb.jsonString(v) }
                sb.append(']')
            }
            sb.append("],").jsonString(content).append(']')
            return sha256(sb.toString().toByteArray()).toHex()
        }

        fun nowSeconds() = System.currentTimeMillis() / 1000

        /** NIP-01 serialization: same escaping as JS JSON.stringify. */
        private fun StringBuilder.jsonString(s: String): StringBuilder {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            return append('"')
        }
    }
}
