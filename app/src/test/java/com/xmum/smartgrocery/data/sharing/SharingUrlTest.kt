package com.xmum.smartgrocery.data.sharing

import org.junit.Assert.*
import org.junit.Test

class SharingUrlTest {
    @Test fun localNetworkAndHttpsAddressesAreAccepted() {
        assertEquals("http://192.168.100.14:8790/", SharingApi.normalizeUrl(" http://192.168.100.14:8790 "))
        assertEquals("http://10.0.2.2:8790/", SharingApi.normalizeUrl("http://10.0.2.2:8790/"))
        assertEquals("https://sharing.example.com/", SharingApi.normalizeUrl("https://sharing.example.com"))
    }
    @Test fun publicHttpAndDisguisedPrivateIpHostnamesAreRejected() {
        for (url in listOf("http://example.com", "http://10.evil.com", "http://192.168.attacker.com", "http://10.0.0.1.attacker.com", "http://010.0.0.1", "http://172.32.0.1"))
            assertTrue("Reject $url", runCatching { SharingApi.normalizeUrl(url) }.isFailure)
    }
    @Test fun credentialsPathsAndQueriesCannotBeSmuggledIntoServerConfiguration() {
        for (url in listOf("http://user:secret@10.0.0.1", "http://10.0.0.1/other", "http://10.0.0.1?token=secret", "http://10.0.0.1/#bad", "http://10.0.0.1:99999"))
            assertTrue("Reject $url", runCatching { SharingApi.normalizeUrl(url) }.isFailure)
    }
}
