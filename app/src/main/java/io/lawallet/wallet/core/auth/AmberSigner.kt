package io.lawallet.wallet.core.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.lawallet.wallet.core.nostr.NostrEvent
import io.lawallet.wallet.core.nostr.Signer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * NIP-55 Android signer (Amber & co.) — the native analogue of the web's NIP-07 extension login.
 * Signs silently through the signer's ContentProvider; falls back to an interactive intent.
 */
class AmberSigner(
    private val context: Context,
    override val pubkey: String,
    private val pkg: String,
    private val launcher: IntentLauncher,
) : Signer {
    /** Bridges to the foreground Activity to run an intent and await its result. */
    fun interface IntentLauncher {
        suspend fun launch(intent: Intent): Intent?
    }

    class Factory(private val context: Context, private val launcher: IntentLauncher) {
        fun create(pubkey: String, pkg: String) = AmberSigner(context, pubkey, pkg, launcher)
    }

    override suspend fun sign(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent {
        val unsigned = buildJsonObject {
            put("pubkey", pubkey); put("created_at", createdAt); put("kind", kind); put("content", content)
            putJsonArray("tags") { tags.forEach { t -> add(kotlinx.serialization.json.JsonArray(t.map { JsonPrimitive(it) })) } }
        }.toString()
        val signedJson = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(Uri.parse("content://$pkg.SIGN_EVENT"), arrayOf(unsigned, "", pubkey), null, null, null)?.use { c ->
                    if (c.moveToFirst() && c.getColumnIndex("rejected") < 0) c.getString(c.getColumnIndexOrThrow("event")) else null
                }
            }.getOrNull()
        } ?: launcher.launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$unsigned")).setPackage(pkg)
                .putExtra("type", "sign_event").putExtra("current_user", pubkey).putExtra("id", "lw-${System.nanoTime()}"),
        )?.getStringExtra("event") ?: throw IllegalStateException("The signer did not sign the request")
        val event = NostrEvent.json.decodeFromString(NostrEvent.serializer(), signedJson)
        check(event.pubkey == pubkey && event.verify()) { "The signer returned an invalid signature" }
        return event
    }

    companion object {
        /** NIP-55 get_public_key intent; result extras: `result` (hex pubkey or npub) + `package`. */
        fun loginIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
            .putExtra("type", "get_public_key")
            .putExtra("permissions", """[{"type":"sign_event","kind":27235},{"type":"sign_event","kind":22242}]""")

        fun isInstalled(context: Context) =
            Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).resolveActivity(context.packageManager) != null
    }
}
