package tsuki.util.decrypt

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
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

    /**
     * Plain AES-GCM (NIST SP 800-38D) decrypt-and-verify.
     *
     * Unlike [AesGcmSiv] and [AesSiv] this is not nonce-misuse-resistant:
     * the caller must not reuse `(key, iv)` across different plaintexts.
     * It is included because some sites
     * emit standard `AES/GCM/NoPadding` and there is no compatible primitive
     * in this package.
     *
     * @param key           16/24/32-byte AES key.
     * @param iv            typically 12 bytes, though JCE accepts other
     *                      lengths for GCM.
     * @param ciphertextWithTag  ciphertext followed by the tag
     *                      (`ct || tag`), matching the JCE wire format.
     * @param tagLengthBits tag length in **bits** (default 128).
     */
    fun aesGcmDecrypt(
        key: ByteArray,
        iv: ByteArray,
        ciphertextWithTag: ByteArray,
        tagLengthBits: Int = 128,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(tagLengthBits, iv),
        )
        return cipher.doFinal(ciphertextWithTag)
    }

    /** Hex-string decoding (`"deadbeef"` to `byteArrayOf(...)`). */
    fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "hex string length must be even" }
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Reads a big-endian 16-bit unsigned integer at [offset]. */
    fun ByteArray.readUShortBigEndian(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)
}