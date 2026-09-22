package com.basetool.bpextractor.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The parsed https-or-loopback rule shared by the ingest client and the device grant (SIB-SEC-09). */
class TransportPolicyTest {

    @Test
    fun `https is allowed for any host`() {
        assertTrue(TransportPolicy.isAllowedServerUrl("https://basetool.example"))
        assertTrue(TransportPolicy.isAllowedServerUrl("https://basetool.example:8443/realms/krt"))
        assertTrue(TransportPolicy.isAllowedServerUrl("HTTPS://Basetool.Example/"))
    }

    @Test
    fun `plain http is allowed for the loopback hosts only`() {
        assertTrue(TransportPolicy.isAllowedServerUrl("http://localhost"))
        assertTrue(TransportPolicy.isAllowedServerUrl("http://localhost:8080/realms/dev"))
        assertTrue(TransportPolicy.isAllowedServerUrl("http://LOCALHOST:8080"))
        assertTrue(TransportPolicy.isAllowedServerUrl("http://127.0.0.1:18090"))
        assertFalse(TransportPolicy.isAllowedServerUrl("http://basetool.example"))
    }

    @Test
    fun `hosts that only look local are refused`() {
        assertFalse(TransportPolicy.isAllowedServerUrl("http://localhost.attacker.tld"))
        assertFalse(TransportPolicy.isAllowedServerUrl("http://localhost.attacker.tld:8080/x"))
        assertFalse(TransportPolicy.isAllowedServerUrl("http://127.0.0.1.attacker.tld/"))
        assertFalse(TransportPolicy.isAllowedServerUrl("http://localhostattacker.tld/"))
    }

    @Test
    fun `user-info in front of the host is refused, even over https`() {
        assertFalse(TransportPolicy.isAllowedServerUrl("http://127.0.0.1@attacker.tld/"))
        assertFalse(TransportPolicy.isAllowedServerUrl("http://localhost@attacker.tld/"))
        assertFalse(TransportPolicy.isAllowedServerUrl("http://localhost:8080@attacker.tld/"))
        assertFalse(TransportPolicy.isAllowedServerUrl("https://basetool.example@attacker.tld/"))
    }

    @Test
    fun `anything that is not an absolute http or https url is refused`() {
        assertFalse(TransportPolicy.isAllowedServerUrl(""))
        assertFalse(TransportPolicy.isAllowedServerUrl("localhost:8080"))
        assertFalse(TransportPolicy.isAllowedServerUrl("/relative/path"))
        assertFalse(TransportPolicy.isAllowedServerUrl("https://"))
        assertFalse(TransportPolicy.isAllowedServerUrl("ftp://localhost/"))
        assertFalse(TransportPolicy.isAllowedServerUrl("file:///C:/x"))
        assertFalse(TransportPolicy.isAllowedServerUrl("http://local host/"))
    }
}
