package tsuki.util.decrypt

/**
 * RFC 8439 AEAD_CHACHA20_POLY1305 and draft-irtf-cfrg-xchacha
 * AEAD_XCHACHA20_POLY1305 decryption.
 */
object ChaCha20Poly1305 {

    /**
     * RFC 8439 §2.8 decrypt-and-verify.
     *
     * Steps:
     *   1. Generate the first 32 bytes of the ChaCha20 stream at counter 0 —
     *      this is the one-time Poly1305 key.
     *   2. Decrypt the ciphertext with ChaCha20 starting at counter 1.
     *   3. Compute Poly1305 over `AAD || pad || ciphertext || pad || len(AAD) || len(CT)`,
     *      where the two length fields are 64-bit little-endian.
     *   4. Constant-time compare the result against [tag]; throw on mismatch.
     *
     * @param key    256-bit ChaCha20 key.
     * @param nonce  96-bit nonce.
     * @param ct     ciphertext (without the tag).
     * @param tag    16-byte Poly1305 tag.
     * @param aad    associated data, may be empty.
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ct: ByteArray,
        tag: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == 32) { "ChaCha20-Poly1305: key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20-Poly1305: nonce must be 12 bytes" }
        require(tag.size == 16) { "ChaCha20-Poly1305: tag must be 16 bytes" }

        val otk = ByteArray(32)
        SecretStream.streamIETF(otk, otk.size, nonce, key)

        val plain = ByteArray(ct.size)
        SecretStream.streamIETFXorIC(plain, ct, ct.size, nonce, 1, key)

        // MAC input = AAD padded to 16 bytes || ciphertext padded to 16 bytes
        //           || le64(aad.size) || le64(ct.size)
        val aadPad = (16 - aad.size % 16) % 16
        val ctPad = (16 - ct.size % 16) % 16
        val macData = Bytes.join(
            aad, ByteArray(aadPad),
            ct, ByteArray(ctPad),
            Bytes.le64(aad.size.toLong()),
            Bytes.le64(ct.size.toLong()),
        )

        val state = SecretStream.Poly1305State()
        SecretStream.poly1305Init(state, otk)
        SecretStream.poly1305Update(state, macData, macData.size)
        val mac = ByteArray(16)
        SecretStream.poly1305Finalize(state, mac)

        require(mac.contentEquals(tag)) { "ChaCha20-Poly1305 authentication failed" }
        return plain
    }

    /**
     * XChaCha20-Poly1305 decrypt (draft-irtf-cfrg-xchacha §3).
     *
     * The 24-byte nonce is split into:
     *   - [0..15]:  fed to HChaCha20 with the key to derive a 32-byte subkey
     *   - [16..23]: the remaining 8 bytes, placed in a 12-byte IETF nonce
     *               as `00 00 00 00 || nonce[16..23]`
     *
     * The subkey + shortened nonce are then used with standard IETF
     * ChaCha20-Poly1305. This keeps a 192-bit nonce (so random nonces are
     * safe) while reusing the regular AEAD core.
     */
    fun xdecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ct: ByteArray,
        tag: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == 32) { "XChaCha20-Poly1305: key must be 32 bytes" }
        require(nonce.size == 24) { "XChaCha20-Poly1305: nonce must be 24 bytes" }

        val subkey = ByteArray(32)
        SecretStream.hchacha20(subkey, nonce.copyOfRange(0, 16), key, null)

        val subNonce = ByteArray(12)
        System.arraycopy(nonce, 16, subNonce, 4, 8)
        return decrypt(subkey, subNonce, ct, tag, aad)
    }
}