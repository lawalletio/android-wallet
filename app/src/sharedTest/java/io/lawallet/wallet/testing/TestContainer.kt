package io.lawallet.wallet.testing

import android.content.Context
import io.lawallet.wallet.app.AppContainer
import io.lawallet.wallet.app.Session
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Builds the app's real object graph against a [FakeInstance]. */
object TestContainer {
    /**
     * An [AppContainer] whose session talks to [instance], as if the user had switched to it (Settings → Instance stores
     * [Session.normalizeUrl] of it before the session reads it). [scope]: where the app's collectors run; the default is
     * the app's own (main thread), JVM tests without a UI usually pass `Dispatchers.Default`.
     * Its OkHttp only reaches localhost: public relays, rates and update checks fail fast instead of leaving the machine.
     * [http]: another client (e.g. [localOnlyHttp] plus an interceptor that maps a Lightning address host onto a fake).
     * On a device the real Android Keystore is already registered, so [FakeAndroidKeyStore.install] does nothing there.
     */
    fun create(
        context: Context,
        instance: FakeInstance,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        http: OkHttpClient = localOnlyHttp(),
    ): AppContainer {
        FakeAndroidKeyStore.install()
        Prefs(context).instanceUrl = Session.normalizeUrl(instance.url)!!
        return AppContainer(context, scope, http)
    }

    /** Plain OkHttp, short timeouts, and any host but localhost refused. */
    fun localOnlyHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val host = chain.request().url.host
            if (host != "localhost" && host != "127.0.0.1" && host != "10.0.2.2") throw IOException("tests only reach localhost")
            chain.proceed(chain.request())
        }
        .build()
}

/** Logs in (as "Log in with Nostr" does) with [secretHex], by default a fresh throwaway key, and returns the key. */
suspend fun AppContainer.loginThrowaway(secretHex: String = FakeInstance.throwawaySecret()): Keys {
    val keys = Keys.fromHex(secretHex)
    session.loginWithSecret(keys.secretHex, "nsec")
    return keys
}

/**
 * An in-memory "AndroidKeyStore" JCA provider for the JVM (Robolectric has none), enough for SecretStore's AES-GCM key.
 * Installing it on a device is a no-op: the real provider is already registered under that name.
 */
object FakeAndroidKeyStore {
    private val keys = Collections.synchronizedMap(HashMap<String, SecretKey>())

    fun install() {
        if (Security.getProvider("AndroidKeyStore") != null) return
        Security.addProvider(@Suppress("DEPRECATION") object : java.security.Provider("AndroidKeyStore", 1.0, "in-memory keystore for tests") {
            init {
                // Services that build their own instances: no lookup by class name through the test's class loader.
                putService(object : Service(this, "KeyStore", "AndroidKeyStore", Store::class.java.name, null, null) {
                    override fun newInstance(constructorParameter: Any?): Any = Store()
                })
                putService(object : Service(this, "KeyGenerator", "AES", AesGenerator::class.java.name, null, null) {
                    override fun newInstance(constructorParameter: Any?): Any = AesGenerator()
                })
            }
        })
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? = keys[alias]?.let { KeyStore.SecretKeyEntry(it) }
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date = Date()
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) { keys[alias] = key as SecretKey }
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String) = keys.containsKey(alias)
        override fun engineSize() = keys.size
        override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String?) = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) = Unit
    }

    class AesGenerator : KeyGeneratorSpi() {
        private var alias: String? = null
        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            alias = (params as? android.security.keystore.KeyGenParameterSpec)?.keystoreAlias
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
        override fun engineGenerateKey(): SecretKey =
            SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES").also { k -> alias?.let { keys[it] = k } }
    }
}
