package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLEncoder

class YouTubeOAuthTest {
    private val clientId = "123456789012-abcdefghijklmnopqrstuvwxyz123456.apps.googleusercontent.com"

    @Test fun `authorization url carries the installed-app parameters`() {
        val url = YouTubeOAuth.authorizationUrl(clientId, "STATE", "CHALLENGE")

        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=$clientId"))
        assertTrue(url.contains("redirect_uri=" + URLEncoder.encode(YOUTUBE_OAUTH_REDIRECT_URI, "UTF-8")))
        assertTrue(
            url.contains(
                "scope=" + URLEncoder.encode("https://www.googleapis.com/auth/youtube.readonly", "UTF-8")
            )
        )
        assertTrue(url.contains("state=STATE"))
        assertTrue(url.contains("code_challenge=CHALLENGE"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("access_type=offline"))
        assertTrue(url.contains("prompt=consent"))
    }

    @Test fun `client id must look like a google cloud oauth client`() {
        assertEquals(clientId, YouTubeOAuth.checkClientId("  $clientId  "))
        listOf("", "not-a-client-id", "spotify-client-id-0123456789abcdef", "https://example.com/client").forEach {
            try {
                YouTubeOAuth.checkClientId(it)
                fail("junk Client ID must be refused: $it")
            } catch (e: YouTubeDataException) {
                assertTrue(e.message!!.contains("Desktop OAuth client"))
            }
        }
    }

    @Test fun `redirect accepts google's scheme path form`() {
        assertEquals(
            "AUTH_CODE",
            YouTubeOAuth.authorizationCode("app.soundtrail:/youtube-auth?code=AUTH_CODE&state=STATE", "STATE")
        )
    }

    @Test fun `redirect accepts the equivalent scheme host form`() {
        assertEquals(
            "AUTH_CODE",
            YouTubeOAuth.authorizationCode("app.soundtrail://youtube-auth?code=AUTH_CODE&state=STATE", "STATE")
        )
    }

    @Test fun `redirect rejects other apps responses`() {
        listOf(
            "app.soundtrail:/spotify-auth?code=AUTH_CODE&state=STATE",
            "app.soundtrail://spotify-auth?code=AUTH_CODE&state=STATE",
            "app.soundtrail:/youtube-auth/extra?code=AUTH_CODE&state=STATE",
            "https://example.com/youtube-auth?code=AUTH_CODE&state=STATE",
            "app.soundtrail://youtube-auth:8080/?code=AUTH_CODE&state=STATE",
            "app.soundtrail://user@youtube-auth?code=AUTH_CODE&state=STATE",
            "app.soundtrail:/youtube-auth?code=AUTH_CODE&state=STATE#fragment",
            "not a uri at all %%%"
        ).forEach {
            try {
                YouTubeOAuth.authorizationCode(it, "STATE")
                fail("foreign redirect must be refused: $it")
            } catch (e: YouTubeDataException) {
                assertEquals("Unexpected YouTube sign-in response.", e.message)
            }
        }
    }

    @Test fun `redirect reports cancellation and failures`() {
        try {
            YouTubeOAuth.authorizationCode("app.soundtrail:/youtube-auth?error=access_denied&state=STATE", "STATE")
            fail("a denied grant must be reported")
        } catch (e: YouTubeDataException) {
            assertEquals("YouTube sign-in was cancelled.", e.message)
        }
        try {
            YouTubeOAuth.authorizationCode(
                "app.soundtrail:/youtube-auth?error=temporarily_unavailable&state=STATE", "STATE"
            )
            fail("a failed grant must be reported")
        } catch (e: YouTubeDataException) {
            assertTrue(e.message!!.contains("temporarily_unavailable"))
        }
    }

    @Test fun `redirect requires the pending state and a code`() {
        try {
            YouTubeOAuth.authorizationCode("app.soundtrail:/youtube-auth?code=AUTH_CODE&state=WRONG", "STATE")
            fail("a state mismatch must be refused")
        } catch (e: YouTubeDataException) {
            assertTrue(e.message!!.contains("could not be verified"))
        }
        try {
            YouTubeOAuth.authorizationCode("app.soundtrail:/youtube-auth?state=STATE", "STATE")
            fail("a missing code must be reported")
        } catch (e: YouTubeDataException) {
            assertTrue(e.message!!.contains("authorization code"))
        }
    }

    @Test fun `token response parses access refresh expiry and scope`() {
        val parsed = YouTubeOAuth.parseToken(
            """{"access_token":"ACCESS","refresh_token":"REFRESH","expires_in":3599,
               "scope":"https://www.googleapis.com/auth/youtube.readonly","token_type":"Bearer"}"""
        )

        assertEquals("ACCESS", parsed.access)
        assertEquals("REFRESH", parsed.refresh)
        assertEquals(3599L, parsed.expiresInSec)
        assertEquals("https://www.googleapis.com/auth/youtube.readonly", parsed.scope)
    }

    @Test fun `token response without a token is refused`() {
        try {
            YouTubeOAuth.parseToken("""{"error":"invalid_grant","error_description":"Bad Request"}""")
            fail("an error response must be refused")
        } catch (e: YouTubeDataException) {
            assertEquals("Bad Request", e.message)
        }
        try {
            YouTubeOAuth.parseToken("not json")
            fail("an unreadable response must be refused")
        } catch (e: YouTubeDataException) {
            assertTrue(e.message!!.contains("unreadable"))
        }
    }

    @Test fun `granted scopes must include the read-only permission`() {
        YouTubeOAuth.checkScope(null)
        YouTubeOAuth.checkScope("https://www.googleapis.com/auth/youtube.readonly")
        YouTubeOAuth.checkScope("openid https://www.googleapis.com/auth/youtube.readonly email")
        try {
            YouTubeOAuth.checkScope("openid email")
            fail("a grant without the read-only scope must be refused")
        } catch (e: YouTubeDataException) {
            assertTrue(e.message!!.contains("read-only permission"))
        }
    }

    @Test fun `missing refresh token parses as null for the caller to reject`() {
        val parsed = YouTubeOAuth.parseToken("""{"access_token":"ACCESS","expires_in":3600}""")

        assertEquals("ACCESS", parsed.access)
        assertNull(parsed.refresh)
    }
}
