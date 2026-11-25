package io.lawallet.wallet.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.lawallet.wallet.testing.FakeAndroidKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class OnchainStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sp get() = context.getSharedPreferences("onchain", Context.MODE_PRIVATE)
    private lateinit var store: OnchainStore

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        sp.edit().clear().commit()
        store = OnchainStore(context)
    }

    @Test fun firstWalletIsPrimaryAndSetPrimaryIsExclusive() {
        val a = store.add(OnchainWallet(name = "A"))
        val b = store.add(OnchainWallet(name = "B"))
        val c = store.add(OnchainWallet(name = "C", isPrimary = true))
        assertTrue(a.isPrimary)
        assertFalse(b.isPrimary)
        assertEquals(listOf(c.id), store.wallets.value.filter { it.isPrimary }.map { it.id })

        store.setPrimary(b.id)
        assertEquals(listOf(b.id), store.wallets.value.filter { it.isPrimary }.map { it.id })
        // Persisted: a fresh store reads the same.
        assertEquals(listOf(b.id), OnchainStore(context).wallets.value.filter { it.isPrimary }.map { it.id })
    }

    @Test fun removingThePrimaryHandsItToTheFirstLeft() {
        val a = store.add(OnchainWallet(name = "A"))
        val b = store.add(OnchainWallet(name = "B"))
        store.remove(a.id)
        assertEquals(listOf(b.id to true), store.wallets.value.map { it.id to it.isPrimary })
    }

    @Test fun removeDropsTheSecret() {
        val phrase = List(12) { "abandon" }.joinToString(" ")
        val w = store.add(OnchainWallet(name = "Seed", words = 12), "$phrase\npass")
        assertEquals(List(12) { "abandon" } to "pass", store.secret(w.id))
        // Kept wrapped, never in the clear.
        assertFalse(sp.all.values.any { it.toString().contains("abandon") })

        store.remove(w.id)
        assertNull(store.secret(w.id))
        assertFalse(sp.all.keys.any { it.startsWith("secret:") })
        assertTrue(store.wallets.value.isEmpty())
    }
}
