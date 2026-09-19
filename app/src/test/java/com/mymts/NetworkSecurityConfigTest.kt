package com.mymts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pins the GENERATED network_security_config.xml (app/build.gradle.kts ->
 * generateNetworkSecurityConfig) for whichever variant this build produced. It reads
 * the emitted file, not the template, so it asserts what the APK actually ships.
 *
 *  - STOCK (no helper URL at build time; what CI builds): the bundled
 *    @raw/helper_cert is an extra base-config trust anchor alongside system CAs, so
 *    first-run setup can validate the helper's self-signed HTTPS listener at whatever
 *    address it is given. Cleartext stays loopback/emulator only.
 *  - OPERATOR (an https helper URL at build time): the per-host pin is unchanged and
 *    the base-config trust set is NOT broadened.
 *
 * Each case runs only in its own variant (Assume), so run the suite once per variant:
 * the default invocation (operator, when local.properties sets an https URL) and
 * `-PMYMTS_HELPER_BASE_URL=` (stock). Failure messages never include the helper host.
 */
class NetworkSecurityConfigTest {

    private val loopbackAliases = listOf("localhost", "127.0.0.1", "10.0.2.2")

    private val helperHost: String? =
        runCatching { URI(BuildConfig.HELPER_BASE_URL).host }.getOrNull()

    // Mirrors the generator's own branch: an https helper URL with a host is pinned.
    private val isOperatorBuild = BuildConfig.HELPER_URL_CONFIGURED &&
        BuildConfig.HELPER_BASE_URL.startsWith("https://") && !helperHost.isNullOrBlank()

    private val isStockBuild = !BuildConfig.HELPER_URL_CONFIGURED

    private val xml: String by lazy {
        // Gradle runs unit tests from the module dir; the root is a fallback.
        listOf(
            File("build/generated/res/nsc/xml/network_security_config.xml"),
            File("app/build/generated/res/nsc/xml/network_security_config.xml"),
        ).firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError(
                "generated network_security_config.xml not found; run via Gradle so " +
                    "generateNetworkSecurityConfig runs first",
            )
    }

    private val root: Element by lazy {
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream()).documentElement
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }
            .filterIsInstance<Element>().filter { it.tagName == tag }

    private val baseConfig: Element get() = root.children("base-config").single()
    private val domainConfigs: List<Element> get() = root.children("domain-config")

    private fun Element.anchors(): List<String> =
        children("trust-anchors").flatMap { it.children("certificates") }
            .map { it.getAttribute("src") }

    private fun Element.domains(): List<String> =
        children("domain").map { it.textContent.trim() }

    private fun assertCleartextOnlyToLoopback() {
        assertEquals("false", baseConfig.getAttribute("cleartextTrafficPermitted"))
        domainConfigs.filter { it.getAttribute("cleartextTrafficPermitted") == "true" }
            .forEach { dc ->
                assertTrue(
                    "a cleartext domain-config names a non-loopback address",
                    loopbackAliases.containsAll(dc.domains()),
                )
                dc.children("domain").forEach {
                    assertEquals("false", it.getAttribute("includeSubdomains"))
                }
            }
    }

    // ---- STOCK ----

    @Test fun `stock - the bundled helper cert is a base-config trust anchor`() {
        assumeTrue("stock variant only", isStockBuild)
        assertTrue(
            "base-config must trust @raw/helper_cert so first-run setup can reach the helper over HTTPS",
            "@raw/helper_cert" in baseConfig.anchors(),
        )
    }

    @Test fun `stock - system CAs stay trusted alongside the helper cert`() {
        assumeTrue("stock variant only", isStockBuild)
        // Additional, not a replacement: public-CA HTTPS must keep working.
        assertEquals(listOf("system", "@raw/helper_cert"), baseConfig.anchors())
    }

    @Test fun `stock - cleartext stays disabled for non-loopback addresses`() {
        assumeTrue("stock variant only", isStockBuild)
        assertCleartextOnlyToLoopback()
    }

    @Test fun `stock - the loopback and emulator cleartext exception is unchanged`() {
        assumeTrue("stock variant only", isStockBuild)
        val block = listOf(
            "    <!-- Demo/local default: helper on http://localhost (or the emulator alias",
            "         10.0.2.2). Cleartext allowed ONLY to loopback/emulator; no pinning. -->",
            "    <domain-config cleartextTrafficPermitted=\"true\">",
            "        <domain includeSubdomains=\"false\">localhost</domain>",
            "        <domain includeSubdomains=\"false\">127.0.0.1</domain>",
            "        <domain includeSubdomains=\"false\">10.0.2.2</domain>",
            "    </domain-config>",
        ).joinToString("\n")
        assertTrue("loopback/emulator exception block is missing or altered", block in xml)
        val cleartext = domainConfigs.filter { it.getAttribute("cleartextTrafficPermitted") == "true" }
        assertEquals(1, cleartext.size)
        assertEquals(loopbackAliases, cleartext.single().domains())
    }

    // ---- OPERATOR ----

    @Test fun `operator - the per-host pin is still emitted unchanged`() {
        assumeTrue("operator (https-pinned) variant only", isOperatorBuild)
        val pin = listOf(
            "    <!-- Helper host pinned to its own self-signed cert (host from local",
            "         config, not committed). System/user CAs are NOT trust anchors here, so",
            "         even a global-CA-signed MITM cert is refused. -->",
            "    <domain-config cleartextTrafficPermitted=\"false\">",
            "        <domain includeSubdomains=\"false\">$helperHost</domain>",
            "        <trust-anchors>",
            "            <certificates src=\"@raw/helper_cert\" />",
            "        </trust-anchors>",
            "    </domain-config>",
        ).joinToString("\n")
        assertTrue("operator per-host pin block is missing or altered", pin in xml)
        val pinned = domainConfigs.filter { helperHost in it.domains() }
        assertEquals(1, pinned.size)
        assertEquals(listOf("@raw/helper_cert"), pinned.single().anchors())
    }

    @Test fun `operator - cleartext stays disabled for non-loopback addresses`() {
        assumeTrue("operator (https-pinned) variant only", isOperatorBuild)
        assertCleartextOnlyToLoopback()
        val pinned = domainConfigs.single { helperHost in it.domains() }
        assertEquals("false", pinned.getAttribute("cleartextTrafficPermitted"))
    }

    @Test fun `operator - the base-config trust set is not broadened`() {
        assumeTrue("operator (https-pinned) variant only", isOperatorBuild)
        // The pinned build already anchors its helper host; every other host keeps
        // system CAs only.
        assertEquals(listOf("system"), baseConfig.anchors())
    }
}
