package tsuki.util.decrypt

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Low-level byte helpers shared by every AEAD primitive.
 *
 * Consolidating these here removes the identical `joinBytes` / `le64` /
 * SHA / HMAC helpers.
 */
internal object Bytes {

    /** Concatenates [parts] into a new byte array. */
    fun join(vararg parts: ByteArray): ByteArray {
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var offset = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, offset, p.size)
            offset += p.size
        }
        return out
    }

    /** 64-bit little-endian encoding (AEAD length field). */
    fun le64(n: Long): ByteArray = ByteArray(8) { ((n ushr (8 * it)) and 0xFF).toByte() }

    /** 16-bit big-endian encoding (Dilar length prefix). */
    fun u16(n: Int): ByteArray =
        byteArrayOf(((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte())

    /** 32-bit big-endian encoding (Dilar ciphertext-length field). */
    fun u32(n: Int): ByteArray =
        byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun toBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun fromBase64Url(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    fun hmac(key: ByteArray, data: ByteArray, algorithm: String = "HmacSHA256"): ByteArray =
        Mac.getInstance(algorithm).apply { init(SecretKeySpec(key, algorithm)) }.doFinal(data)

    fun sha256(d: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(d)
    fun sha384(d: ByteArray): ByteArray = MessageDigest.getInstance("SHA-384").digest(d)
    fun sha512(d: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(d)
}