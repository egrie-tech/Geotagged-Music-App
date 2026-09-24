package tech.egrie.soundtrail.integrations

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 7636 S256: no embedded client secret is needed in a native app. */
internal object Pkce {
    fun verifier(): String = randomUrlSafe(64)
    fun state(): String = randomUrlSafe(32)

    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun randomUrlSafe(size: Int): String = ByteArray(size).also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}
