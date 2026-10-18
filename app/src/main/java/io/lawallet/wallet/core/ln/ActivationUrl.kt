package io.lawallet.wallet.core.ln

import java.net.URI

/** `<host>/wallet/activate/<tokenId>` (trailing slash tolerated). Port of activation-url.ts. */
data class ActivationUrl(val tokenId: String, val host: String) {
    companion object {
        private val TOKEN = Regex("^[A-Za-z0-9_-]+$")

        fun parse(text: String): ActivationUrl? = runCatching {
            val uri = URI(text.trim())
            if (uri.scheme != "http" && uri.scheme != "https") return null
            val segs = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
            if (segs.size != 3 || segs[0] != "wallet" || segs[1] != "activate" || !TOKEN.matches(segs[2])) return null
            val host = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
            ActivationUrl(segs[2], host.lowercase())
        }.getOrNull()
    }
}
