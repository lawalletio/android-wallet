package io.lawallet.wallet.app

import io.lawallet.wallet.core.fx.Rates
import io.lawallet.wallet.core.fx.YadioRates
import io.lawallet.wallet.core.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Yadio rates with a 60s TTL and a persisted last value (use-yadio-ticker.ts). */
class RatesRepository(private val yadio: YadioRates, private val prefs: Prefs, scope: CoroutineScope) {
    private val ser = MapSerializer(String.serializer(), Double.serializer())
    private val _rates = MutableStateFlow(prefs.ratesJson?.let { runCatching { Json.decodeFromString(ser, it) }.getOrNull() })
    val rates: StateFlow<Rates?> = _rates
    val active = MutableStateFlow(false)

    init {
        scope.launch {
            while (true) {
                if (active.value) runCatching { yadio.fetch() }.onSuccess {
                    _rates.value = it
                    prefs.ratesJson = Json.encodeToString(ser, it)
                }
                delay(60_000)
            }
        }
    }
}
