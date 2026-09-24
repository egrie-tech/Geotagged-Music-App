package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test fun `S256 matches RFC 7636 example`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun `verifier and state are random URL-safe values`() {
        assertTrue(Pkce.verifier().matches(Regex("[A-Za-z0-9_-]{86}")))
        assertTrue(Pkce.state().matches(Regex("[A-Za-z0-9_-]{43}")))
        assertTrue(Pkce.verifier() != Pkce.verifier())
    }
}
