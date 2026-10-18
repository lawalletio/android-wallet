package io.lawallet.wallet.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Shapes follow packages/sdk/src/types.ts (the route implementations), not the OpenAPI snapshot.

@Serializable
data class InstanceSettings(
    val domain: String? = null,
    val endpoint: String? = null,
    val brand_theme: String? = null,
    val brand_rounding: String? = null,
    val community_name: String? = null,
    val logotype_url: String? = null,
    val isotypo_url: String? = null,
    val community_cover_url: String? = null,
    val maintenance_enabled: String? = null,
    val lncurl_enabled: String? = null,
    val lncurl_auto_create: String? = null,
) {
    val lncurlAutoCreate get() = lncurl_auto_create == "true"
    val lncurlEnabled get() = lncurl_enabled == "true"
}

@Serializable data class VersionInfo(val currentVersion: String? = null)
@Serializable data class JwtResponse(val token: String, val expiresIn: JsonElement? = null)
@Serializable data class JwtInfo(val valid: Boolean = false, val pubkey: String? = null, val role: String? = null, val expiresAt: String? = null)

@Serializable data class CurrencyPrefs(val active: List<String>, val selected: String)

@Serializable
data class CurrentUser(
    val userId: String,
    val lightningAddress: String? = null,
    val nwcString: String? = null,
    val effectiveNwcString: String? = null,
    val primaryAddressMode: String? = null,
    val primaryUsername: String? = null,
    val currencyPrefs: CurrencyPrefs? = null,
) {
    /** The connection the wallet uses: effectiveNwcString || nwcString (wallet-nwc.ts). */
    val nwc: String? get() = effectiveNwcString?.takeIf { it.isNotBlank() } ?: nwcString?.takeIf { it.isNotBlank() }
}

@Serializable
data class WalletAddress(
    val username: String,
    val mode: String = "IDLE",
    val redirect: String? = null,
    val remoteWalletId: String? = null,
    val remoteWalletName: String? = null,
    val isPrimary: Boolean = false,
    val nwcMode: String = "NONE",
    val createdAt: String? = null,
)

@Serializable data class UsernameAvailability(val available: Boolean, val username: String = "")

@Serializable
data class RegistrationInvoice(
    val id: String = "",
    val bolt11: String = "",
    val paymentHash: String = "",
    val amountSats: Long = 0,
    val verify: String? = null,
    val expiresAt: String? = null,
    val free: Boolean = false,
)

@Serializable data class ClaimInvoiceResult(val success: Boolean = false, val lightningAddress: String? = null)

@Serializable
data class RemoteWallet(
    val id: String,
    val name: String,
    val type: String = "NWC",
    val status: String = "ACTIVE",
    val isDefault: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val diedAt: String? = null,
    val diedReason: String? = null,
    val provider: String? = null,
    val lncurlServerUrl: String? = null,
    val receiveCapabilities: ReceiveCapabilities? = null,
    val isOwner: Boolean? = null,
)

@Serializable data class ReceiveCapabilities(val lud21: Boolean = true, val nip57: Boolean = false, val receiptPubkey: String? = null, val reason: String? = null)
@Serializable data class ConnectionString(val connectionString: String)
@Serializable data class WalletBalance(val balanceSats: Long)

@Serializable data class CardDesign(val id: String = "", val imageUrl: String? = null, val description: String? = null)
@Serializable data class Ntag424Info(val cid: String? = null, val ctr: Long? = null)

@Serializable
data class WalletCard(
    val id: String,
    val design: CardDesign? = null,
    val ntag424: Ntag424Info? = null,
    val createdAt: String? = null,
    val title: String? = null,
    val lastUsedAt: String? = null,
    val username: String? = null,
    val remoteWalletId: String? = null,
    val defaultRemoteWalletId: String? = null,
    val kind: String = "SIMPLE",
    val blocked: Boolean = false,
    val disabled: Boolean = false,
)

@Serializable
data class ActivationPreview(
    val tokenId: String = "",
    val qrKind: String = "ONE_TIME",
    val status: String = "PENDING",
    val card: ActivationCard? = null,
)

@Serializable data class ActivationCard(val id: String = "", val title: String? = null, val kind: String = "SIMPLE", val design: CardDesign? = null)

@Serializable
data class ActivationClaim(
    val qrKind: String = "ONE_TIME",
    val card: ActivationCard? = null,
    val bonuses: ActivationBonuses? = null,
    val needsLightningAddress: Boolean = false,
)

@Serializable data class ActivationBonuses(val freeLightningAddress: Boolean = false, val sats: SatsBonus? = null)
@Serializable data class SatsBonus(val granted: Boolean = false, val amountSats: Long? = null)

@Serializable
data class PasskeyCredential(
    val id: String,
    val label: String? = null,
    val deviceType: String? = null,
    val backedUp: Boolean = false,
    val pubkey: String? = null,
    val createdAt: String? = null,
    val lastUsedAt: String? = null,
)

@Serializable data class PasskeyCredentials(val credentials: List<PasskeyCredential> = emptyList())
@Serializable data class PasskeyOptions(val options: JsonObject)

@Serializable
data class OAuthGrant(
    val id: String,
    val clientName: String,
    val scopes: List<String> = emptyList(),
    val spendLimitSats: Long? = null,
    val createdAt: String? = null,
    val lastUsedAt: String? = null,
)

@Serializable data class OAuthGrants(val grants: List<OAuthGrant> = emptyList())

@Serializable
data class NostrProfile(
    val pubkey: String,
    val name: String? = null,
    val displayName: String? = null,
    val picture: String? = null,
    val nip05: String? = null,
    val lud16: String? = null,
)

@Serializable data class NostrProfiles(val profiles: List<NostrProfile> = emptyList())

@Serializable
data class WalletNotification(
    val id: String,
    val name: String = "",
    val channel: String = "WEBHOOK",
    val action: String = "RECEIVED",
    val enabled: Boolean = true,
    val webhookUrl: String? = null,
    val kind: Int? = null,
    val pTag: String? = null,
    val relays: List<String>? = null,
    val nip44: Boolean? = null,
)

@Serializable data class AccountSummary(val role: String? = null)
