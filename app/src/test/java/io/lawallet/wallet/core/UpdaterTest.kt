package io.lawallet.wallet.core

import io.lawallet.wallet.app.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun versions() {
        assertTrue(Updater.isNewer("1.0.1", "1.0.0"))
        assertTrue(Updater.isNewer("1.0.10", "1.0.9"))
        assertTrue(Updater.isNewer("1.1", "1.0.9"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.0"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.1"))
        assertFalse(Updater.isNewer("1.0", "1.0.0"))
        assertTrue(Updater.isNewer("1.0.0", "0.0.0-abc1234")) // CI non-tag builds
    }

    private val assets = listOf(
        "lawallet-1.0.1-arm64-v8a.apk", "lawallet-1.0.1-armeabi-v7a.apk",
        "lawallet-1.0.1-universal.apk", "lawallet-1.0.1-x86_64.apk", "SHA256SUMS.txt",
    )

    @Test
    fun picksTheDevicesAbi() {
        assertEquals("lawallet-1.0.1-arm64-v8a.apk", Updater.pickApk(assets, listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals("lawallet-1.0.1-armeabi-v7a.apk", Updater.pickApk(assets, listOf("armeabi-v7a", "armeabi")))
        assertEquals("lawallet-1.0.1-universal.apk", Updater.pickApk(assets, listOf("riscv64")))
        assertNull(Updater.pickApk(listOf("SHA256SUMS.txt"), listOf("arm64-v8a")))
    }

    @Test
    fun readsSha256sums() {
        val sums = "D91373C5  lawallet-1.0.1-arm64-v8a.apk\n8dfc3bbe *lawallet-1.0.1-armeabi-v7a.apk\n"
        assertEquals("d91373c5", Updater.expectedSha256(sums, "lawallet-1.0.1-arm64-v8a.apk"))
        assertEquals("8dfc3bbe", Updater.expectedSha256(sums, "lawallet-1.0.1-armeabi-v7a.apk"))
        assertNull(Updater.expectedSha256(sums, "lawallet-1.0.1-x86_64.apk"))
    }
}
