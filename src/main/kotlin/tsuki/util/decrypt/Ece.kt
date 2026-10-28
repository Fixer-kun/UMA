package tsuki.util.decrypt

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val CURVE_NAME = "secp256r1"
private val ECE_KEY_INFO = "Content-Encoding: aes128gcm\u0000".toByteArray()
private val ECE_NONCE_INFO = "Content-Encoding: nonce\u0000".toByteArray()

/**
 * HKDF, P-256 key helpers, and an RFC 8188 Encrypted Content-Encoding
 * ("aes128gcm") decryptor.
 *
 *   - [hkdf] for deriving keys and nonces from an ECDH shared secret
 *   - [publicKeyToRaw]/[rawToPublicKey] for the 65-byte uncompressed form
 *   - [decrypt] for a full RFC 8188 payload, in case a site returns one
 *     directly instead of the Dilar-style per-version ECIES wrapper.
 */
object Ece {

    /**
     * RFC 8188 decryption.
     *
     * Header (21 bytes + optional key-id):
     *   [0..15]  salt
     *   [16..19] record size, 32-bit big-endian
     *   [20]     key-id length
     *   then `key-id-length` bytes of key-id
     *
     * Then N records. Each record carries up to `record-size` bytes and is
     * AES-128-GCM encrypted with a nonce that XORs the record sequence
     * number into the low 32 bits of the base nonce. The last byte of each
     * decrypted record is a delimiter: 0x01 for non-final, 0x02 for final,
     * followed by zero padding to the full record size.
     *
     * @param payload full RFC 8188 ciphertext, header included.
     * @param ikm     raw ECDH secret used as the HKDF input keying material.
     */
    fun decrypt(payload: ByteArray, ikm: ByteArray): ByteArray {
        require(payload.size >= 21) { "ece: payload shorter than the header" }
        val salt = payload.copyOfRange(0, 16)
        val recordSize = ByteBuffer.wrap(payload, 16, 4).int

        // Skip past the key-id to the first record.
        var pos = 21 + (payload[20].toInt() and 0xFF)
        require(recordSize >= 18 && pos < payload.size) { "ece: malformed header" }

        val key = SecretKeySpec(hkdf(ikm, salt, ECE_KEY_INFO, 16), "AES")
        val nonce = hkdf(ikm, salt, ECE_NONCE_INFO, 12)

        val out = ByteArrayOutputStream()
        var sequence = 0
        while (pos < payload.size) {
            val record = payload.copyOfRange(pos, pos + minOf(recordSize, payload.size - pos))
            pos += record.size
            require(record.size >= 18) { "ece: record $sequence too short" }

            // Nonce = base nonce with seq XORed into the low 32 bits.
            val iv = nonce.copyOf()
            for (i in 0..3) {
                iv[8 + i] = (iv[8 + i].toInt() xor (sequence ushr (8 * (3 - i)))).toByte()
            }

            val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                doFinal(record)
            }

            // Trim trailing zero padding, then check the delimiter (0x01 or 0x02).
            var last = plain.size - 1
            while (last >= 0 && plain[last].toInt() == 0) last--
            val isFinal = pos >= payload.size
            require(last >= 0 && plain[last].toInt() == if (isFinal) 2 else 1) {
                "ece: record $sequence has the wrong delimiter"
            }

            out.write(plain, 0, last)
            sequence++
        }
        return out.toByteArray()
    }

    /**
     * HKDF (RFC 5869).
     *
     * Extract: PRK = HMAC(salt, IKM)
     * Expand:  T(0) = empty
     *          T(i) = HMAC(PRK, T(i-1) || info || i)
     *          OKM  = first `length` bytes of T(1) || T(2) || ...
     *
     * An empty `salt` is treated as HashLen zero bytes per the RFC. The
     * output length is bounded by 255 * HashLen.
     *
     * @param algorithm one of "HmacSHA256" (default), "HmacSHA384", "HmacSHA512".
     */
    fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
        algorithm: String = "HmacSHA256",
    ): ByteArray {
        val mac = Mac.getInstance(algorithm)
        val effectiveSalt = salt.takeIf { it.isNotEmpty() } ?: ByteArray(mac.macLength)

        mac.init(SecretKeySpec(effectiveSalt, algorithm))
        val prk = mac.doFinal(ikm)

        require(length <= 255 * mac.macLength) { "hkdf: length exceeds 255 * HashLen" }
        mac.init(SecretKeySpec(prk, algorithm))

        val okm = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (okm.size() < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            okm.write(previous)
            counter++
        }
        return okm.toByteArray().copyOf(length)
    }

    /**
     * Serialises a P-256 public key to the 65-byte uncompressed form
     * `04 || X || Y` that JOSE and ECIES peers expect on the wire.
     */
    fun publicKeyToRaw(publicKey: ECPublicKey): ByteArray =
        byteArrayOf(4) + publicKey.w.affineX.toFixedBytes(32) + publicKey.w.affineY.toFixedBytes(32)

    /**
     * Parses the 65-byte uncompressed P-256 form back into an [ECPublicKey].
     *
     * Rejects inputs whose point does not lie on the curve, which protects
     * against the classic invalid-curve attack on ECDH.
     */
    fun rawToPublicKey(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "Invalid P-256 raw public key" }

        val params = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(CURVE_NAME))
        }.getParameterSpec(ECParameterSpec::class.java)

        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))

        // y^2 == x^3 + ax + b (mod p) for P-256 (a = -3).
        val p = (params.curve.field as ECFieldFp).p
        require(y * y % p == (x * x * x + params.curve.a * x + params.curve.b) % p) {
            "Invalid P-256 raw public key: point is not on the curve"
        }

        return KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }
}

/** Left-pads [this] to exactly [length] bytes, or truncates from the left if longer. */
private fun BigInteger.toFixedBytes(length: Int): ByteArray {
    val raw = toByteArray()
    return when {
        raw.size == length -> raw
        raw.size > length -> raw.copyOfRange(raw.size - length, raw.size)
        else -> ByteArray(length - raw.size) + raw
    }
}