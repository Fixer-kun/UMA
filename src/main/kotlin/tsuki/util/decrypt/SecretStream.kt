package tsuki.util.decrypt

/**
 * Portions of this software are derived from libsodium.
 * Source: https://github.com/jedisct1/libsodium
 * Copyright (c) 2013-2024 Frank Denis <j at pureftpd dot org>
 *
 * Provides the two building blocks needed for the RFC 8439 (IETF
 * ChaCha20-Poly1305) and draft-irtf-cfrg-xchacha (XChaCha20-Poly1305) AEAD
 * constructions. The AEAD composition itself lives in [ChaCha20Poly1305].
 */
internal object SecretStream {

    // ChaCha20

    private const val ROUNDS = 20
    private const val POLY_HIGH_BIT = 1L shl 24

    /**
     * The 20-round ARX permutation (RFC 8439 §2.3), in-place on [state].
     *
     * Runs 10 iterations of (4 column rounds + 4 diagonal rounds) over the
     * 16-word state. Used both by the feed-forward stream functions
     * ([streamIETF], [streamIETFXorIC]) and by [hchacha20], which omits
     * the final feed-forward step.
     */
    private fun chachaRounds(state: IntArray) {
        var x0 = state[0]; var x1 = state[1]; var x2 = state[2]; var x3 = state[3]
        var x4 = state[4]; var x5 = state[5]; var x6 = state[6]; var x7 = state[7]
        var x8 = state[8]; var x9 = state[9]; var x10 = state[10]; var x11 = state[11]
        var x12 = state[12]; var x13 = state[13]; var x14 = state[14]; var x15 = state[15]

        var i = 0
        while (i < ROUNDS) {
            // Column rounds
            x0 += x4; x12 = rotl32(x12 xor x0, 16); x8 += x12; x4 = rotl32(x4 xor x8, 12)
            x0 += x4; x12 = rotl32(x12 xor x0, 8);  x8 += x12; x4 = rotl32(x4 xor x8, 7)
            x1 += x5; x13 = rotl32(x13 xor x1, 16); x9 += x13; x5 = rotl32(x5 xor x9, 12)
            x1 += x5; x13 = rotl32(x13 xor x1, 8);  x9 += x13; x5 = rotl32(x5 xor x9, 7)
            x2 += x6; x14 = rotl32(x14 xor x2, 16); x10 += x14; x6 = rotl32(x6 xor x10, 12)
            x2 += x6; x14 = rotl32(x14 xor x2, 8);  x10 += x14; x6 = rotl32(x6 xor x10, 7)
            x3 += x7; x15 = rotl32(x15 xor x3, 16); x11 += x15; x7 = rotl32(x7 xor x11, 12)
            x3 += x7; x15 = rotl32(x15 xor x3, 8);  x11 += x15; x7 = rotl32(x7 xor x11, 7)

            // Diagonal rounds
            x0 += x5; x15 = rotl32(x15 xor x0, 16); x10 += x15; x5 = rotl32(x5 xor x10, 12)
            x0 += x5; x15 = rotl32(x15 xor x0, 8);  x10 += x15; x5 = rotl32(x5 xor x10, 7)
            x1 += x6; x12 = rotl32(x12 xor x1, 16); x11 += x12; x6 = rotl32(x6 xor x11, 12)
            x1 += x6; x12 = rotl32(x12 xor x1, 8);  x11 += x12; x6 = rotl32(x6 xor x11, 7)
            x2 += x7; x13 = rotl32(x13 xor x2, 16); x8 += x13; x7 = rotl32(x7 xor x8, 12)
            x2 += x7; x13 = rotl32(x13 xor x2, 8);  x8 += x13; x7 = rotl32(x7 xor x8, 7)
            x3 += x4; x14 = rotl32(x14 xor x3, 16); x9 += x14; x4 = rotl32(x4 xor x9, 12)
            x3 += x4; x14 = rotl32(x14 xor x3, 8);  x9 += x14; x4 = rotl32(x4 xor x9, 7)
            i += 2
        }

        state[0] = x0; state[1] = x1; state[2] = x2; state[3] = x3
        state[4] = x4; state[5] = x5; state[6] = x6; state[7] = x7
        state[8] = x8; state[9] = x9; state[10] = x10; state[11] = x11
        state[12] = x12; state[13] = x13; state[14] = x14; state[15] = x15
    }

    /**
     * Initialises an IETF ChaCha20 state (RFC 8439 §2.3):
     *
     *   state[0..3]   = constants "expand 32-byte k"
     *   state[4..11]  = 256-bit key, little-endian
     *   state[12]     = block counter
     *   state[13..15] = 96-bit nonce, little-endian
     */
    private fun initIetfState(state: IntArray, key: ByteArray, counter: Int, nonce: ByteArray) {
        state[0] = 0x61707865; state[1] = 0x3320646e
        state[2] = 0x79622d32; state[3] = 0x6b206574
        state[4] = load32Le(key, 0); state[5] = load32Le(key, 4)
        state[6] = load32Le(key, 8); state[7] = load32Le(key, 12)
        state[8] = load32Le(key, 16); state[9] = load32Le(key, 20)
        state[10] = load32Le(key, 24); state[11] = load32Le(key, 28)
        state[12] = counter
        state[13] = load32Le(nonce, 0); state[14] = load32Le(nonce, 4); state[15] = load32Le(nonce, 8)
    }

    /**
     * Generic IETF ChaCha20 stream loop shared by [streamIETF] and
     * [streamIETFXorIC]. Both drivers only differ in what they do with
     * each 64-byte keystream block, so the state setup, round call, and
     * counter advance are factored out here.
     *
     * [onBlock] is invoked once per 64-byte block (last block may be
     * shorter) with the block bytes, its offset in the output, and its
     * length.
     */
    private inline fun chacha20KeystreamBlocks(
        key: ByteArray,
        nonce: ByteArray,
        startCounter: Int,
        totalBytes: Int,
        onBlock: (blockBytes: ByteArray, offset: Int, length: Int) -> Unit,
    ) {
        val input = IntArray(16).also { initIetfState(it, key, startCounter, nonce) }
        val work = IntArray(16)
        val blockBytes = ByteArray(64)

        var pos = 0
        while (pos < totalBytes) {
            System.arraycopy(input, 0, work, 0, 16)
            chachaRounds(work)
            for (j in 0 until 16) store32Le(blockBytes, j * 4, work[j] + input[j])
            input[12]++

            val length = minOf(totalBytes - pos, 64)
            onBlock(blockBytes, pos, length)
            pos += 64
        }
    }

    /**
     * IETF ChaCha20 keystream generation (RFC 8439 §2.4).
     *
     * `c` receives `clen` bytes of keystream — this is the raw stream, not
     * an XOR of any plaintext. Used directly to derive the one-time
     * Poly1305 key (the first 32 bytes of the stream).
     */
    fun streamIETF(c: ByteArray, clen: Int, nonce: ByteArray, key: ByteArray) {
        chacha20KeystreamBlocks(key, nonce, 0, clen) { block, offset, length ->
            System.arraycopy(block, 0, c, offset, length)
        }
    }

    /**
     * IETF ChaCha20 encryption/decryption (RFC 8439 §2.4) with an explicit
     * initial counter `ic`.
     *
     * In ChaCha20-Poly1305 the plaintext keystream starts at counter 1 (the
     * block at counter 0 is reserved for the Poly1305 key), which is why
     * callers pass `ic = 1`.
     */
    fun streamIETFXorIC(
        c: ByteArray,
        m: ByteArray,
        mlen: Int,
        nonce: ByteArray,
        ic: Int,
        key: ByteArray,
    ) {
        chacha20KeystreamBlocks(key, nonce, ic, mlen) { block, offset, length ->
            for (j in 0 until length) {
                c[offset + j] = (m[offset + j].toInt() xor block[j].toInt()).toByte()
            }
        }
    }

    /**
     * HChaCha20 (draft-irtf-cfrg-xchacha §2.2).
     *
     * A reduced ChaCha core that produces only the internal state words
     * {0,1,2,3,12,13,14,15} (32 bytes) after 20 rounds, without the final
     * feed-forward addition. Used to derive the subkey for XChaCha20:
     *
     *     subkey = HChaCha20(key, nonce[0..15])
     *
     * `constant` may be null, in which case the standard "expand 32-byte k"
     * constants are used. `input` must be 16 bytes (the first half of the
     * 24-byte XChaCha nonce), placed in state words 12-15.
     */
    fun hchacha20(out: ByteArray, input: ByteArray, key: ByteArray, constant: ByteArray?) {
        val state = IntArray(16)

        if (constant == null) {
            state[0] = 0x61707865; state[1] = 0x3320646e
            state[2] = 0x79622d32; state[3] = 0x6b206574
        } else {
            state[0] = load32Le(constant, 0); state[1] = load32Le(constant, 4)
            state[2] = load32Le(constant, 8); state[3] = load32Le(constant, 12)
        }
        state[4] = load32Le(key, 0); state[5] = load32Le(key, 4)
        state[6] = load32Le(key, 8); state[7] = load32Le(key, 12)
        state[8] = load32Le(key, 16); state[9] = load32Le(key, 20)
        state[10] = load32Le(key, 24); state[11] = load32Le(key, 28)
        state[12] = load32Le(input, 0); state[13] = load32Le(input, 4)
        state[14] = load32Le(input, 8); state[15] = load32Le(input, 12)

        chachaRounds(state)
        // No feed-forward for HChaCha20.

        store32Le(out, 0, state[0]); store32Le(out, 4, state[1])
        store32Le(out, 8, state[2]); store32Le(out, 12, state[3])
        store32Le(out, 16, state[12]); store32Le(out, 20, state[13])
        store32Le(out, 24, state[14]); store32Le(out, 28, state[15])
    }

    /**
     * Poly1305 running state (RFC 8439 §2.5).
     *
     * `r0..r4` hold the clamped 130-bit multiplier in 26-bit limbs.
     * `h0..h4` hold the current accumulator in 26-bit limbs.
     * `pad0..pad3` hold the 128-bit final pad `s`.
     * `buffer`/`leftover` buffer partial blocks between update calls.
     *
     * 26-bit limbs keep each multiplication within a 64-bit long without
     * overflowing, so no 128-bit arithmetic is required.
     */
    class Poly1305State {
        var r0 = 0L; var r1 = 0L; var r2 = 0L; var r3 = 0L; var r4 = 0L
        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L
        var pad0 = 0L; var pad1 = 0L; var pad2 = 0L; var pad3 = 0L
        val buffer = ByteArray(16)
        var leftover = 0
    }

    /**
     * Reads four consecutive 32-bit little-endian words from [src] at
     * [offset] and invokes [action] with them. Used by both the r-key
     * clamping in [poly1305Init] and the per-block accumulation in
     * [poly1305AbsorbBlocks]; extracting it removes the four-line
     * duplication between those two call sites.
     *
     * The lambda is expected to have side effects (either writing to
     * [Poly1305State] fields or updating the accumulator `var`s in the
     * caller), which is why [action] returns `Unit`.
     */
    private inline fun withQuad(
        src: ByteArray,
        offset: Int,
        action: (Long, Long, Long, Long) -> Unit,
    ) {
        action(
            load32LeLong(src, offset),
            load32LeLong(src, offset + 4),
            load32LeLong(src, offset + 8),
            load32LeLong(src, offset + 12),
        )
    }

    /**
     * Poly1305 initialization (RFC 8439 §2.5).
     *
     * Derives the 256-bit one-time key `(r, s)` from the 32-byte input.
     * The clamping masks are applied to `r`:
     *   r &= 0x0ffffffc0ffffffc0ffffffc0fffffff
     * which zeroes specific bits so the resulting value is a multiple of 4
     * and the low bits do not threaten the 26-bit limb arithmetic.
     */
    fun poly1305Init(state: Poly1305State, key: ByteArray) {
        withQuad(key, 0) { t0, t1, t2, t3 ->
            state.r0 = t0 and 0x3ffffff
            state.r1 = ((t0 ushr 26) or (t1 shl 6)) and 0x3ffff03
            state.r2 = ((t1 ushr 20) or (t2 shl 12)) and 0x3ffc0ff
            state.r3 = ((t2 ushr 14) or (t3 shl 18)) and 0x3f03fff
            state.r4 = (t3 ushr 8) and 0x00fffff
        }

        state.h0 = 0; state.h1 = 0; state.h2 = 0; state.h3 = 0; state.h4 = 0
        state.pad0 = load32LeLong(key, 16)
        state.pad1 = load32LeLong(key, 20)
        state.pad2 = load32LeLong(key, 24)
        state.pad3 = load32LeLong(key, 28)
        state.leftover = 0
    }

    fun poly1305Update(state: Poly1305State, m: ByteArray, mlen: Int) =
        poly1305Update(state, m, 0, mlen)

    /**
     * Absorbs `mlen` bytes of [m] starting at [offset] into the state.
     *
     * Partial blocks are buffered; only full 16-byte blocks are fed to
     * [poly1305AbsorbBlocks]. Leftover bytes are carried over to the next
     * call, which is required by the streaming contract.
     */
    fun poly1305Update(state: Poly1305State, m: ByteArray, offset: Int, mlen: Int) {
        var pos = offset
        var remaining = mlen

        if (state.leftover > 0) {
            var want = 16 - state.leftover
            if (want > remaining) want = remaining
            System.arraycopy(m, pos, state.buffer, state.leftover, want)
            remaining -= want
            pos += want
            state.leftover += want
            if (state.leftover < 16) return
            poly1305AbsorbBlocks(state, state.buffer, 0, 16, POLY_HIGH_BIT)
            state.leftover = 0
        }

        if (remaining >= 16) {
            val want = remaining and 15.inv()
            poly1305AbsorbBlocks(state, m, pos, want, POLY_HIGH_BIT)
            pos += want
            remaining -= want
        }

        if (remaining > 0) {
            System.arraycopy(m, pos, state.buffer, 0, remaining)
            state.leftover = remaining
        }
    }

    /**
     * Finalizes the MAC (RFC 8439 §2.5.2).
     *
     * A partial trailing block, if any, is padded with 0x01 followed by
     * zero bytes and absorbed as a full block WITHOUT the high bit (that
     * is why the padding byte 0x01 is placed at the end of the data —
     * it stands in for the 2^128 high bit). The accumulator is then
     * reduced to a canonical 128-bit value and `s` (the pad) is added
     * modulo 2^128 to produce the 16-byte tag.
     */
    fun poly1305Finalize(state: Poly1305State, mac: ByteArray) {
        if (state.leftover > 0) {
            state.buffer[state.leftover] = 1
            for (i in state.leftover + 1 until 16) state.buffer[i] = 0
            poly1305AbsorbBlocks(state, state.buffer, 0, 16, 0L)
        }

        var h0 = state.h0
        var h1 = state.h1
        var h2 = state.h2
        var h3 = state.h3
        var h4 = state.h4

        // Propagate carries through the five limbs so each fits in 26 bits.
        val c0 = h1 ushr 26; h1 = h1 and 0x3ffffff
        val h2c = h2 + c0; val c1 = h2c ushr 26; h2 = h2c and 0x3ffffff
        val h3c = h3 + c1; val c2 = h3c ushr 26; h3 = h3c and 0x3ffffff
        val h4c = h4 + c2; val c3 = h4c ushr 26; h4 = h4c and 0x3ffffff
        h0 += c3 * 5
        val c4 = h0 ushr 26; h0 = h0 and 0x3ffffff; h1 += c4

        // Constant-time reduction: compute h + (2^130 - 5) and pick it if
        // h >= p, using the borrow bit as a mask to avoid branches.
        val g0 = h0 + 5
        val gc0 = g0 ushr 26; val g0m = g0 and 0x3ffffff
        val g1 = h1 + gc0
        val gc1 = g1 ushr 26; val g1m = g1 and 0x3ffffff
        val g2 = h2 + gc1
        val gc2 = g2 ushr 26; val g2m = g2 and 0x3ffffff
        val g3 = h3 + gc2
        val gc3 = g3 ushr 26; val g3m = g3 and 0x3ffffff
        val g4 = h4 + gc3 - (1L shl 26)

        val mask = (g4 ushr 63) - 1
        val maskInv = mask.inv()
        h0 = (h0 and maskInv) or (g0m and mask)
        h1 = (h1 and maskInv) or (g1m and mask)
        h2 = (h2 and maskInv) or (g2m and mask)
        h3 = (h3 and maskInv) or (g3m and mask)
        h4 = (h4 and maskInv) or (g4 and mask)

        // Reassemble the 128-bit accumulator from 26-bit limbs.
        h0 = (h0 or (h1 shl 26)) and 0xffffffffL
        h1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        h2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        h3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL

        // Final tag = (h + s) mod 2^128.
        var f = h0 + state.pad0; h0 = f and 0xffffffffL
        f = h1 + state.pad1 + (f ushr 32); h1 = f and 0xffffffffL
        f = h2 + state.pad2 + (f ushr 32); h2 = f and 0xffffffffL
        f = h3 + state.pad3 + (f ushr 32); h3 = f and 0xffffffffL

        store32Le(mac, 0, h0.toInt()); store32Le(mac, 4, h1.toInt())
        store32Le(mac, 8, h2.toInt()); store32Le(mac, 12, h3.toInt())
    }

    /**
     * Absorbs [bytes] bytes of [m] starting at [offset] into [state].
     *
     * [hibit] is OR'd into the top limb of each block before the field
     * multiplication: pass [POLY_HIGH_BIT] for regular blocks (the
     * implicit 2^128 term) and `0L` for the padded final block produced
     * by [poly1305Finalize], where the padding byte 0x01 already stands
     * in for that bit.
     *
     * The lambda passed to [withQuad] only mutates the running accumulator
     * (`h0..h4`). The five product terms `d0..d4` are computed AFTER the
     * lambda returns, which avoids the Kotlin restriction that a `var`
     * assigned only inside a lambda is not considered definitely assigned
     * by the compiler's flow analysis.
     */
    private fun poly1305AbsorbBlocks(
        state: Poly1305State,
        m: ByteArray,
        offset: Int,
        bytes: Int,
        hibit: Long,
    ) {
        val r0 = state.r0; val r1 = state.r1; val r2 = state.r2; val r3 = state.r3; val r4 = state.r4
        var h0 = state.h0; var h1 = state.h1; var h2 = state.h2; var h3 = state.h3; var h4 = state.h4
        val s1 = r1 * 5; val s2 = r2 * 5; val s3 = r3 * 5; val s4 = r4 * 5

        var pos = offset
        var remaining = bytes
        while (remaining >= 16) {
            // absorb the block into the accumulator (mutates h0..h4).
            withQuad(m, pos) { t0, t1, t2, t3 ->
                h0 += t0 and 0x3ffffff
                h1 += ((t0 ushr 26) or (t1 shl 6)) and 0x3ffffff
                h2 += ((t1 ushr 20) or (t2 shl 12)) and 0x3ffffff
                h3 += ((t2 ushr 14) or (t3 shl 18)) and 0x3ffffff
                h4 += (t3 ushr 8) or hibit
            }

            // 5x5 field multiplication (reads h0..h4, no mutation).
            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            val d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            val d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            val d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            val d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

            // propagate carries through the five 26-bit limbs.
            h0 = d0 and 0x3ffffff
            val c0 = d0 ushr 26

            val sum1 = d1 + c0
            h1 = sum1 and 0x3ffffff
            val c1 = sum1 ushr 26

            val sum2 = d2 + c1
            h2 = sum2 and 0x3ffffff
            val c2 = sum2 ushr 26

            val sum3 = d3 + c2
            h3 = sum3 and 0x3ffffff
            val c3 = sum3 ushr 26

            val sum4 = d4 + c3
            h4 = sum4 and 0x3ffffff
            h0 += (sum4 ushr 26) * 5

            val c4 = h0 ushr 26
            h0 = h0 and 0x3ffffff
            h1 += c4

            pos += 16
            remaining -= 16
        }

        state.h0 = h0; state.h1 = h1; state.h2 = h2; state.h3 = h3; state.h4 = h4
    }

    /** primitives */
    private fun load32Le(src: ByteArray, offset: Int): Int =
        (src[offset].toInt() and 0xFF) or
                ((src[offset + 1].toInt() and 0xFF) shl 8) or
                ((src[offset + 2].toInt() and 0xFF) shl 16) or
                ((src[offset + 3].toInt() and 0xFF) shl 24)

    private fun load32LeLong(src: ByteArray, offset: Int): Long =
        (src[offset].toLong() and 0xFF) or
                ((src[offset + 1].toLong() and 0xFF) shl 8) or
                ((src[offset + 2].toLong() and 0xFF) shl 16) or
                ((src[offset + 3].toLong() and 0xFF) shl 24)

    private fun store32Le(dst: ByteArray, offset: Int, w: Int) {
        dst[offset] = (w and 0xFF).toByte()
        dst[offset + 1] = ((w ushr 8) and 0xFF).toByte()
        dst[offset + 2] = ((w ushr 16) and 0xFF).toByte()
        dst[offset + 3] = ((w ushr 24) and 0xFF).toByte()
    }

    private fun rotl32(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))
}
