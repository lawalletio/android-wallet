package io.lawallet.wallet.core

import io.lawallet.wallet.core.api.LaWalletApi
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.fx.Keypad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Locale

class CurrencyKeypadTest {
    private val rates = mapOf("USD" to 100_000.0, "ARS" to 100_000_000.0)
    private val sat = Currencies.keypadMode("SAT")
    private val fiat = Currencies.keypadMode("USD")

    private fun type(mode: io.lawallet.wallet.core.fx.KeypadMode, vararg keys: String) = keys.fold("0") { v, k -> Keypad.press(v, k, mode) }

    @Test fun satKeypad() {
        assertEquals("1203", type(sat, "1", "2", "0", "3"))
        assertEquals("120", type(sat, "1", "2", "0", "3", Keypad.BACKSPACE))
        assertEquals("0", type(sat, "1", Keypad.BACKSPACE))
        assertEquals("999999999999", type(sat, *Array(14) { "9" }))
    }

    @Test fun fiatKeypadIsCashRegister() {
        assertEquals("0.01", type(fiat, "1"))
        assertEquals("12.34", type(fiat, "1", "2", "3", "4"))
        assertEquals("1.23", type(fiat, "1", "2", "3", "4", Keypad.BACKSPACE))
        assertEquals("1.00", type(fiat, "1", "00"))
    }

    @Test fun conversions() {
        assertEquals(100L, Currencies.parseToSats("100", "SAT", null))
        assertEquals(1000L, Currencies.parseToSats("0.00001", "BTC", null))
        assertEquals(1000L, Currencies.parseToSats("1.00", "USD", rates)) // $1 at $100k/BTC
        assertEquals(1L, Currencies.parseToSats("0.0001", "USD", rates))   // ceil, min 1
        assertNull(Currencies.parseToSats("1.00", "EUR", rates))
        assertNull(Currencies.parseToSats("0", "SAT", null))
        assertEquals("1.00", Currencies.inputFromSats(1000, "USD", rates))
        assertEquals("0.00001", Currencies.inputFromSats(1000, "BTC", rates))
    }

    @Test fun formatting() {
        val us = Locale.US
        assertEquals("21,000", Currencies.format(21_000, "SAT", null, us))
        assertEquals("0.00021000", Currencies.format(21_000, "BTC", null, us))
        assertEquals("21.00", Currencies.format(21_000, "USD", rates, us))
        assertEquals("< 0.01", Currencies.format(1, "USD", rates, us))
        assertEquals("—", Currencies.format(1, "EUR", rates, us))
        assertEquals("sats", Currencies.unitLabel("SAT"))
    }

    @Test fun jwtExpiry() {
        fun jwt(exp: Long) = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":$exp}""".toByteArray()) + ".s"
        val now = 1_700_000_000_000L
        assertFalse(LaWalletApi.expiresSoon(jwt(now / 1000 + 3600), now))
        assertTrue(LaWalletApi.expiresSoon(jwt(now / 1000 + 60), now))
        assertTrue(LaWalletApi.expiresSoon("garbage", now))
    }
}
