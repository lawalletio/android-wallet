package io.lawallet.wallet.core.auth

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import io.lawallet.wallet.core.api.LaWalletApi
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.nostr.NostrEvent
import io.lawallet.wallet.core.nostr.randomBytes
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

class PasskeyException(val kind: String, message: String) : Exception(message)

/**
 * Passkeys via Credential Manager with the WebAuthn PRF extension — the same derivation as the
 * web (passkey-prf.ts), so a passkey made on the web logs in here to the same account.
 */
class Passkeys(private val api: () -> LaWalletApi, private val rpId: () -> String) {
    data class Identity(val secretHex: String, val credentialId: String)

    private val b64u = Base64.getUrlEncoder().withoutPadding()
    private fun b64uDecode(s: String) = Base64.getUrlDecoder().decode(s)

    /** Discoverable assertion → PRF → nsec. No server round trip (same as the web). */
    suspend fun authenticate(activity: Activity, credentialId: String? = null): Identity {
        val request = buildJsonObject {
            put("challenge", b64u.encodeToString(randomBytes(32)))
            put("rpId", rpId())
            put("userVerification", "required")
            put("timeout", 300_000)
            if (credentialId != null) putJsonArray("allowCredentials") {
                add(buildJsonObject { put("type", "public-key"); put("id", credentialId) })
            }
            putJsonObject("extensions") {
                putJsonObject("prf") { putJsonObject("eval") { put("first", b64u.encodeToString(PasskeyPrf.salt())) } }
            }
        }.toString()
        val result = try {
            CredentialManager.create(activity).getCredential(activity, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(request))))
        } catch (e: Exception) {
            throw translate(e)
        }
        val json = (result.credential as? PublicKeyCredential)?.authenticationResponseJson
            ?: throw PasskeyException("failed", "No passkey was returned")
        val resp = NostrEvent.json.parseToJsonElement(json).jsonObject
        val first = prfFirst(resp) ?: throw PasskeyException("prf-unsupported", PRF_UNSUPPORTED)
        return Identity(PasskeyPrf.deriveSecretHex(b64uDecode(first)), resp["id"]!!.jsonPrimitive.content)
    }

    /**
     * Registration = server options → create credential (asking PRF at create) → derive key →
     * prove it with a kind-22242 event answering the challenge → server verifies.
     * [authenticated] = add a passkey to the signed-in account instead of signing up.
     */
    suspend fun register(activity: Activity, label: String?, authenticated: Boolean): Identity {
        val options = try {
            api().passkeyRegistrationOptions(label).options
        } catch (e: Exception) {
            throw PasskeyException("server", e.message ?: "Could not start passkey registration")
        }
        val challenge = options["challenge"]!!.jsonPrimitive.content
        val withPrf = JsonObject(options + ("extensions" to buildJsonObject {
            (options["extensions"] as? JsonObject)?.forEach { (k, v) -> put(k, v) }
            putJsonObject("prf") { putJsonObject("eval") { put("first", b64u.encodeToString(PasskeyPrf.salt())) } }
        }))
        val created = try {
            CredentialManager.create(activity).createCredential(activity, CreatePublicKeyCredentialRequest(withPrf.toString())) as CreatePublicKeyCredentialResponse
        } catch (e: Exception) {
            throw translate(e)
        }
        val reg = NostrEvent.json.parseToJsonElement(created.registrationResponseJson).jsonObject
        val ext = reg["clientExtensionResults"]?.jsonObject
        val prf = ext?.get("prf")?.jsonObject
        val enabled = prf?.get("enabled")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        val firstAtCreate = prfFirst(reg)
        if (enabled == false && firstAtCreate == null) throw PasskeyException("prf-unsupported", PRF_UNSUPPORTED)
        val identity = if (firstAtCreate != null) Identity(PasskeyPrf.deriveSecretHex(b64uDecode(firstAtCreate)), reg["id"]!!.jsonPrimitive.content)
        else authenticate(activity, reg["id"]!!.jsonPrimitive.content)

        val keys = Keys.fromHex(identity.secretHex)
        val proof = NostrEvent.sign(keys, 22242, "", listOf(listOf("challenge", challenge)))
        // The PRF output is key material: only `enabled` ever goes to the server.
        val sanitized = JsonObject(reg + ("clientExtensionResults" to buildJsonObject {
            ext?.filterKeys { it != "prf" }?.forEach { (k, v) -> put(k, v) }
            if (enabled != null) putJsonObject("prf") { put("enabled", enabled) }
        }))
        try {
            api().passkeyRegistrationVerify(buildJsonObject {
                put("challenge", challenge)
                put("credential", sanitized)
                put("pubkey", keys.pubkey)
                put("proof", NostrEvent.json.parseToJsonElement(proof.toJson()))
                if (label != null) put("label", label)
            }, authenticated)
        } catch (e: Exception) {
            throw PasskeyException("server", e.message ?: "The server rejected the passkey")
        }
        return identity
    }

    private fun prfFirst(resp: JsonObject): String? =
        (resp["clientExtensionResults"] as? JsonObject)?.get("prf")?.let { it as? JsonObject }
            ?.get("results")?.let { it as? JsonObject }?.get("first")?.jsonPrimitive?.contentOrNull

    companion object {
        const val PRF_UNSUPPORTED = "This passkey provider can't derive keys (PRF). Use Google Password Manager or another provider with PRF support."

        /**
         * Type checks, not class names: R8 renames androidx.credentials' exceptions in release
         * builds, so name matching sent every cancel/no-passkey to the raw framework message.
         */
        internal fun translate(e: Exception): PasskeyException {
            val dom = (e as? CreatePublicKeyCredentialDomException)?.domError ?: (e as? GetPublicKeyCredentialDomException)?.domError
            return when {
                // NotAllowedError = closed or timed out (WebAuthn folds both into it; the web treats it as cancel too).
                e is GetCredentialCancellationException || e is CreateCredentialCancellationException || dom is NotAllowedError ->
                    PasskeyException("cancelled", "Passkey request was cancelled")
                e is NoCredentialException -> PasskeyException("no-credential", "No passkey found for this wallet on this device")
                dom is InvalidStateError -> PasskeyException("duplicate", "This device already has a passkey for this account")
                dom is SecurityError -> PasskeyException("rp", "This app isn't authorized for passkeys on this server (assetlinks.json)")
                e is CreateCredentialNoCreateOptionException || e is CreateCredentialProviderConfigurationException ||
                    e is GetCredentialProviderConfigurationException || e is CreateCredentialUnsupportedException || e is GetCredentialUnsupportedException ->
                    PasskeyException("unsupported", "No passkey provider is set up on this device — add a Google account and a screen lock")
                else -> PasskeyException("failed", e.message ?: "Passkey request failed")
            }
        }
    }
}
