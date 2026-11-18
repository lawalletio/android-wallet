package io.lawallet.wallet.core

import io.lawallet.wallet.core.ln.Lnurl
import io.lawallet.wallet.core.ln.PluginLink
import io.lawallet.wallet.core.ln.ScanResult
import io.lawallet.wallet.core.ln.ScanRouter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plugin install links from the scanner, deep links and paste. */
class PluginScanTest {
    private val folder = "https://github.com/lawalletio/mobile-plugins/tree/main/plugins/fiwind"
    private val encoded = "https%3A%2F%2Fgithub.com%2Flawalletio%2Fmobile-plugins%2Ftree%2Fmain%2Fplugins%2Ffiwind"

    @Test fun parsesThePayloadAndBareFolderUrls() {
        assertEquals(folder, PluginLink.parse("lawallet:plugin?url=$encoded")?.url)
        assertEquals(folder, PluginLink.parse("LAWALLET:plugin?url=$encoded")?.url)
        assertEquals(folder, PluginLink.parse("lawallet:plugin?v=1&url=$encoded")?.url)
        assertEquals(folder, PluginLink.parse(folder)?.url)
        // A ref with slashes stays intact; the resolver splits ref and dir.
        val branch = "https://github.com/o/r/tree/claude/x/plugins/y"
        assertEquals(branch, PluginLink.parse("lawallet:plugin?url=" + java.net.URLEncoder.encode(branch, "UTF-8"))?.url)
    }

    @Test fun rejectsNonGitHubAndNonHttps() {
        for (bad in listOf(
            "http://github.com/lawalletio/mobile-plugins/tree/main/plugins/fiwind",
            "https://evil.example/plugins/fiwind/manifest.json",
            "https://github.com.evil.example/o/r/tree/main",
            "https://github.com/o/r/blob/main/plugins/x/index.js",
            "github.com/o/r/tree/main",
            "javascript:alert(1)",
        )) assertNull(bad, PluginLink.parse("lawallet:plugin?url=" + java.net.URLEncoder.encode(bad, "UTF-8"))!!.url)
        assertNull(PluginLink.parse("lawallet:plugin")!!.url)
        assertNull(PluginLink.parse("lawallet:plugin?foo=bar")!!.url)
    }

    @Test fun otherPayloadsAreNotPluginLinks() {
        assertNull(PluginLink.parse("https://github.com/lawalletio/mobile-plugins"))
        assertNull(PluginLink.parse("http://github.com/o/r/tree/main"))
        assertNull(PluginLink.parse("lightning:lnbc1"))
        assertNull(PluginLink.parse("https://beta.lawallet.io/wallet/activate/abc"))
    }

    @Test fun routesToAddPluginOnlyInPluginBuilds() = runBlocking {
        val lnurl = Lnurl(OkHttpClient())
        val on = ScanRouter(lnurl, plugins = true)
        val off = ScanRouter(lnurl, plugins = false)
        assertEquals(ScanResult.InstallPlugin(folder), on.route(" lawallet:plugin?url=$encoded\n", emptySet()))
        assertEquals(ScanResult.InstallPlugin(folder), on.route(folder, emptySet()))
        assertEquals(ScanResult.PluginsUnavailable, off.route("lawallet:plugin?url=$encoded", emptySet()))
        assertEquals(ScanResult.PluginsUnavailable, off.route(folder, emptySet()))
        val bad = on.route("lawallet:plugin?url=https%3A%2F%2Fevil.example%2Fmanifest.json", emptySet())
        assertEquals(PluginLink.BAD_URL, (bad as ScanResult.Error).code)
        // Payment payloads are untouched.
        assertTrue(on.route("just some text", emptySet()) is ScanResult.Error)
    }
}
