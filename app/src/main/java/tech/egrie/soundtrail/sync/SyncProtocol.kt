package tech.egrie.soundtrail.sync

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One last-write-wins entity version. Payload is JSON text and is encrypted before upload. */
internal data class SyncRecord(
    val kind: String,
    val entityId: String,
    val clock: Long,
    val deviceId: String,
    val deleted: Boolean,
    val payload: String?
) {
    val key: String get() = "$kind:$entityId"

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("entityId", entityId)
        put("clock", clock)
        put("deviceId", deviceId)
        put("deleted", deleted)
        if (payload != null) put("payload", JSONObject(payload))
    }

    companion object {
        fun fromJson(json: JSONObject): SyncRecord {
            val kind = json.getString("kind")
            val entityId = json.getString("entityId")
            val clock = json.getLong("clock")
            val deviceId = json.getString("deviceId")
            val deleted = json.getBoolean("deleted")
            val payload = json.optJSONObject("payload")?.toString()
            require(kind in SUPPORTED_KINDS) { "Unknown synced record kind." }
            require(entityId.isNotBlank() && entityId.length <= 128)
            require(clock >= 0L && deviceId.isNotBlank() && deviceId.length <= 128)
            require(deleted || payload != null) { "An active sync record needs a payload." }
            return SyncRecord(kind, entityId, clock, deviceId, deleted, payload)
        }
    }
}

internal const val SYNC_KIND_PIN = "pin"
internal const val SYNC_KIND_PLAYLIST = "playlist"
internal const val SYNC_KIND_PLAYLIST_ITEM = "playlistItem"
internal const val SYNC_KIND_AUTOMATION = "automation"
internal const val SYNC_KIND_KEY_CHECK = "keyCheck"
private val SUPPORTED_KINDS = setOf(
    SYNC_KIND_PIN, SYNC_KIND_PLAYLIST, SYNC_KIND_PLAYLIST_ITEM, SYNC_KIND_AUTOMATION, SYNC_KIND_KEY_CHECK
)

/** Opaque encrypted event as it travels between the device and the server. */
internal data class EncryptedSyncEvent(
    val id: String,
    val iv: String,
    val ciphertext: String,
    val sequence: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("iv", iv)
        put("ciphertext", ciphertext)
    }

    companion object {
        fun fromJson(json: JSONObject): EncryptedSyncEvent = EncryptedSyncEvent(
            id = json.getString("id"), iv = json.getString("iv"),
            ciphertext = json.getString("ciphertext"), sequence = json.optLong("sequence", 0L)
        )
    }
}

/**
 * Client-only E2E encryption. A random 256-bit recovery key is generated on account creation;
 * AES-GCM keys never leave the device, and the server sees only a fresh IV and ciphertext.
 * The event UUID is authenticated as additional data so a swapped server row won't decrypt.
 */
/** Encrypted full-state checkpoint used to compact an account's older event history. */
internal data class EncryptedSyncSnapshot(
    val id: String,
    val throughCursor: Long,
    val iv: String,
    val ciphertext: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("throughCursor", throughCursor)
        put("iv", iv)
        put("ciphertext", ciphertext)
    }

    companion object {
        fun fromJson(json: JSONObject): EncryptedSyncSnapshot {
            val id = json.getString("id")
            java.util.UUID.fromString(id)
            val throughCursor = json.getLong("throughCursor")
            require(throughCursor >= 0L)
            val iv = json.getString("iv")
            val ciphertext = json.getString("ciphertext")
            require(iv.isNotBlank() && ciphertext.isNotBlank())
            return EncryptedSyncSnapshot(id, throughCursor, iv, ciphertext)
        }
    }
}

internal object SyncCrypto {
    private const val AES_KEY_BYTES = 32
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun newRecoveryKey(): ByteArray = ByteArray(AES_KEY_BYTES).also(random::nextBytes)

    fun encodeRecoveryKey(key: ByteArray): String {
        require(key.size == AES_KEY_BYTES) { "Recovery key must contain 256 bits." }
        return encoder.encodeToString(key)
    }

    fun decodeRecoveryKey(encoded: String): ByteArray {
        val decoded = runCatching { decoder.decode(encoded.trim()) }
            .getOrElse { throw IllegalArgumentException("That recovery key is not valid.") }
        require(decoded.size == AES_KEY_BYTES) { "Recovery key must be the 43-character key Soundtrail generated." }
        return decoded
    }

    fun encrypt(record: SyncRecord, key: ByteArray, eventId: String): EncryptedSyncEvent {
        require(key.size == AES_KEY_BYTES)
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(eventId.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(record.toJson().toString().toByteArray(StandardCharsets.UTF_8))
        return EncryptedSyncEvent(eventId, encoder.encodeToString(iv), encoder.encodeToString(ciphertext))
    }

    fun decrypt(event: EncryptedSyncEvent, key: ByteArray): SyncRecord {
        require(key.size == AES_KEY_BYTES)
        val iv = decoder.decode(event.iv)
        require(iv.size == GCM_IV_BYTES) { "Invalid sync event IV." }
        val ciphertext = decoder.decode(event.ciphertext)
        require(ciphertext.size >= GCM_TAG_BITS / 8) { "Invalid sync event ciphertext." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(event.id.toByteArray(StandardCharsets.UTF_8))
        val clear = cipher.doFinal(ciphertext)
        return SyncRecord.fromJson(JSONObject(String(clear, StandardCharsets.UTF_8)))
    }

    fun encryptSnapshot(
        records: Collection<SyncRecord>, key: ByteArray, snapshotId: String, throughCursor: Long
    ): EncryptedSyncSnapshot {
        require(key.size == AES_KEY_BYTES && throughCursor >= 0L)
        java.util.UUID.fromString(snapshotId)
        val payload = JSONObject()
            .put("format", SNAPSHOT_FORMAT)
            .put("throughCursor", throughCursor)
            .put("records", JSONArray().also { array -> records.forEach { array.put(it.toJson()) } })
            .toString().toByteArray(StandardCharsets.UTF_8)
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(snapshotAad(snapshotId, throughCursor))
        val ciphertext = cipher.doFinal(payload)
        return EncryptedSyncSnapshot(
            id = snapshotId,
            throughCursor = throughCursor,
            iv = encoder.encodeToString(iv),
            ciphertext = encoder.encodeToString(ciphertext)
        )
    }

    fun decryptSnapshot(snapshot: EncryptedSyncSnapshot, key: ByteArray): List<SyncRecord> {
        require(key.size == AES_KEY_BYTES && snapshot.throughCursor >= 0L)
        java.util.UUID.fromString(snapshot.id)
        val iv = decoder.decode(snapshot.iv)
        require(iv.size == GCM_IV_BYTES) { "Invalid sync snapshot IV." }
        val ciphertext = decoder.decode(snapshot.ciphertext)
        require(ciphertext.size >= GCM_TAG_BITS / 8) { "Invalid sync snapshot ciphertext." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(snapshotAad(snapshot.id, snapshot.throughCursor))
        val json = JSONObject(String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8))
        require(json.optString("format") == SNAPSHOT_FORMAT) { "Unsupported sync snapshot format." }
        require(json.getLong("throughCursor") == snapshot.throughCursor) { "Snapshot cursor mismatch." }
        val records = json.getJSONArray("records")
        val decoded = (0 until records.length()).map { index ->
            SyncRecord.fromJson(records.getJSONObject(index))
        }
        require(decoded.map(SyncRecord::key).distinct().size == decoded.size) { "Duplicate entity in sync snapshot." }
        return decoded
    }

    private fun snapshotAad(id: String, cursor: Long) =
        "soundtrail-snapshot-v1:$id:$cursor".toByteArray(StandardCharsets.UTF_8)

    private const val SNAPSHOT_FORMAT = "soundtrail-snapshot-v1"
}

/**
 * Pure deterministic merge for additions/updates/deletes. Higher Lamport clock wins;
 * ties are broken by device id so two offline edits converge on every device.
 */
internal object SyncMerge {
    fun merge(current: Map<String, SyncRecord>, incoming: Iterable<SyncRecord>): Map<String, SyncRecord> {
        val merged = current.toMutableMap()
        incoming.forEach { candidate ->
            require(candidate.kind in SUPPORTED_KINDS)
            val old = merged[candidate.key]
            if (old == null || compare(candidate, old) > 0) merged[candidate.key] = candidate
        }
        return merged
    }

    fun compare(a: SyncRecord, b: SyncRecord): Int = when {
        a.clock != b.clock -> a.clock.compareTo(b.clock)
        a.deviceId != b.deviceId -> a.deviceId.compareTo(b.deviceId)
        a.deleted != b.deleted -> if (a.deleted) 1 else -1
        else -> (a.payload ?: "").compareTo(b.payload ?: "")
    }
}
