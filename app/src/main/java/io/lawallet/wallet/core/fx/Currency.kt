package io.lawallet.wallet.core.fx

import io.lawallet.wallet.core.ln.getJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/** BTC→fiat rates, e.g. {"USD": 65000.0}. */
typealias Rates = Map<String, Double>

object Currencies {
    /** currencies-store.ts catalog. SAT is locked first. */
    val CATALOG = listOf("SAT", "BTC", "ARS", "BRL", "CLP", "COP", "EUR", "GBP", "JPY", "MXN", "PEN", "USD", "UYU", "VES")
    val DEFAULT_ACTIVE = listOf("SAT", "USD", "ARS")
    const val DEFAULT_SELECTED = "SAT"

    fun unitLabel(code: String) = if (code == "SAT") "sats" else code

    fun displayName(code: String, locale: Locale = Locale.getDefault()): String = when (code) {
        "SAT" -> "Satoshis"
        "BTC" -> "Bitcoin"
        else -> runCatching { java.util.Currency.getInstance(code).getDisplayName(locale) }.getOrDefault(code)
    }

    fun convertSats(sats: Long, code: String, rates: Rates?): Double? = when (code) {
        "SAT" -> sats.toDouble()
        "BTC" -> sats / 100_000_000.0
        else -> rates?.get(code)?.takeIf { it.isFinite() }?.let { sats / 100_000_000.0 * it }
    }

    private fun fmt(value: Double, minFrac: Int, maxFrac: Int, locale: Locale): String =
        DecimalFormat("#,##0", DecimalFormatSymbols(locale)).apply {
            minimumFractionDigits = minFrac
            maximumFractionDigits = maxFrac
            roundingMode = RoundingMode.HALF_EVEN
        }.format(value)

    /** format-sats.ts `formatSatsAmount`: `—` without a rate, `< 0.01` for dust fiat. */
    fun format(sats: Long, code: String, rates: Rates?, locale: Locale = Locale.getDefault()): String {
        val v = convertSats(sats, code, rates) ?: return "—"
        return when {
            code == "SAT" -> fmt(v.roundToLong().toDouble(), 0, 0, locale)
            code == "BTC" -> fmt(v, 8, 8, locale)
            v != 0.0 && abs(v) < 0.01 -> "< 0.01"
            else -> fmt(v, 2, 2, locale)
        }
    }

    fun formatSats(sats: Long, locale: Locale = Locale.getDefault()) = fmt(sats.toDouble(), 0, 0, locale)

    /** amount-currency.ts keypadOptionsForCurrency. */
    fun keypadMode(code: String) = when (code) {
        "SAT" -> KeypadMode(integerOnly = true, fixedDecimals = null, maxDecimals = null)
        "BTC" -> KeypadMode(integerOnly = false, fixedDecimals = null, maxDecimals = 8)
        else -> KeypadMode(integerOnly = false, fixedDecimals = 2, maxDecimals = null)
    }

    /** amount-currency.ts parseAmountToSats: ceil, min 1. */
    fun parseToSats(raw: String, code: String, rates: Rates?): Long? {
        val value = Keypad.parse(raw) ?: return null
        return when (code) {
            "SAT" -> ceilPositive(value)
            "BTC" -> ceilPositive(value * 100_000_000)
            else -> {
                val rate = rates?.get(code)?.takeIf { it.isFinite() && it > 0 } ?: return null
                ceilPositive(value / rate * 100_000_000)
            }
        }
    }

    fun inputFromSats(sats: Long?, code: String, rates: Rates?): String {
        if (sats == null || sats <= 0) return "0"
        if (code == "SAT") return sats.toString()
        val c = convertSats(sats, code, rates) ?: return "0"
        return if (code == "BTC") BigDecimal(c).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        else String.format(Locale.US, "%.2f", if (c > 0 && c < 0.01) 0.01 else c)
    }

    private fun ceilPositive(raw: Double): Long? {
        if (!raw.isFinite() || raw <= 0) return null
        val r = Math.round(raw)
        val sats = if (abs(raw - r) < 1e-9) r else ceil(raw).toLong()
        return max(1, sats)
    }
}

data class KeypadMode(val integerOnly: Boolean, val fixedDecimals: Int?, val maxDecimals: Int?)

/** amount-keypad.tsx press logic. Keys: "0".."9", "00", "backspace". */
object Keypad {
    const val BACKSPACE = "backspace"

    fun parse(raw: String): Double? {
        if (raw.isEmpty() || raw == "." || raw == "0") return null
        return raw.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    }

    fun press(value: String, key: String, mode: KeypadMode, maxDigits: Int = 12): String {
        mode.fixedDecimals?.takeIf { it > 0 }?.let { return pressFixed(value, key, it, maxDigits) }
        if (key == BACKSPACE) return if (value.length <= 1) "0" else value.dropLast(1)
        if (value.isEmpty() || value == "0") return if (key == "00") "0" else key
        val intPart = value.substringBefore('.')
        if (value.contains('.') && mode.maxDecimals != null) {
            val remaining = mode.maxDecimals - value.substringAfter('.').length
            if (remaining <= 0) return value
            return value + key.take(remaining)
        }
        if (!value.contains('.') && intPart.length >= maxDigits) return value
        val remaining = if (value.contains('.')) key.length else maxDigits - intPart.length
        return value + key.take(remaining)
    }

    private fun pressFixed(value: String, key: String, decimals: Int, maxDigits: Int): String {
        val current = minorDigits(value, decimals)
        if (key == BACKSPACE) return formatFixed(if (current.length <= 1) "0" else current.dropLast(1), decimals)
        val base = if (current == "0") "" else current
        val remaining = maxDigits + decimals - base.length
        if (remaining <= 0) return value
        return formatFixed((base + key.take(remaining)).ifEmpty { "0" }, decimals)
    }

    private fun minorDigits(raw: String, decimals: Int): String {
        val i = raw.substringBefore('.').filter { it.isDigit() }
        val d = raw.substringAfter('.', "").filter { it.isDigit() }.padEnd(decimals, '0').take(decimals)
        return (i + d).trimStart('0').ifEmpty { "0" }
    }

    private fun formatFixed(digits: String, decimals: Int): String {
        val normalized = digits.filter { it.isDigit() }.trimStart('0').ifEmpty { "0" }
        val padded = normalized.padStart(decimals + 1, '0')
        val intPart = padded.dropLast(decimals).trimStart('0').ifEmpty { "0" }
        return intPart + "." + padded.takeLast(decimals)
    }
}

/** Yadio rates, fetched straight from the device like the web (use-yadio-ticker.ts). */
class YadioRates(private val http: OkHttpClient) {
    suspend fun fetch(): Rates {
        val obj = http.getJsonObject("https://api.yadio.io/exrates/BTC")
        return obj["BTC"]!!.jsonObject.mapNotNull { (k, v) -> v.jsonPrimitive.doubleOrNull?.let { k to it } }.toMap()
    }
}
