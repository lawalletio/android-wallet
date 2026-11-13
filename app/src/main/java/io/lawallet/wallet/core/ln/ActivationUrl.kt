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

/** `<host>/wallet/auth/email#token=<token>`: the instance's magic link (token in the fragment, never the query). */
data class EmailLoginUrl(val token: String, val host: String) {
    companion object {
        private val TOKEN = Regex("^[A-Za-z0-9_-]{40,64}$")

        fun parse(text: String): EmailLoginUrl? = runCatching {
            val uri = URI(text.trim())
            if (uri.scheme != "http" && uri.scheme != "https") return null
            if (uri.rawPath?.trimEnd('/') != "/wallet/auth/email") return null
            val token = uri.rawFragment.orEmpty().split('&').firstNotNullOfOrNull { it.removePrefix("token=").takeIf { t -> t != it } }
            if (token == null || !TOKEN.matches(token)) return null
            val host = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
            EmailLoginUrl(token, host.lowercase())
        }.getOrNull()
    }
}
