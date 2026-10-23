package io.lawallet.wallet.app

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

fun domainAvatar(domain: String) = "https://raw.githubusercontent.com/lawalletio/static/main/public/img/domains/${if (domain == "lawallet.io") "lawallet.ar" else domain}.png"

/**
 * Recipient avatars by Lightning Address (payment-destinations.ts `useAddressAvatar`).
 * LUD-06 metadata images are base64 `data:` URIs, which Coil only memory-caches, so each one is
 * written to a file once; http pictures and domain logos ride Coil's disk cache.
 */
class Avatars(context: Context) {
    private val dir = File(context.filesDir, "avatars")
    // Listed once so rows never touch the disk while composing.
    private val saved: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { dir.list()?.let { addAll(it) } }

    private fun name(address: String) = address.trim().lowercase().replace(Regex("[^a-z0-9@._-]"), "_")

    suspend fun save(address: String, dataUri: String?) {
        val bytes = dataUri?.takeIf { it.startsWith("data:image/") }?.substringAfter("base64,", "")
            ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: return
        val name = name(address)
        withContext(Dispatchers.IO) { dir.mkdirs(); File(dir, name).writeBytes(bytes) }
        saved += name
    }

    /** Saved LUD-06 image, then the contact's picture, then the domain logo. */
    fun of(address: String, picture: String? = null): String =
        name(address).let { if (it in saved) "file://${File(dir, it).absolutePath}" else null }
            ?: picture ?: domainAvatar(address.substringAfter('@'))

    fun clear() { saved.clear(); dir.deleteRecursively() }
}
