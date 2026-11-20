package io.lawallet.wallet.core

import kotlinx.serialization.json.Json

/** Our own stored JSON (prefs caches, plugin records, events): unknown fields from older/newer versions are skipped. */
val relaxedJson = Json { ignoreUnknownKeys = true }

/** Server and wallet payloads (LaWallet API, NIP-47): also omits nulls and coerces bad values to defaults. */
val apiJson = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
