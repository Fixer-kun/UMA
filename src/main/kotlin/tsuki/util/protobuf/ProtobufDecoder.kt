@file:OptIn(ExperimentalSerializationApi::class)

package tsuki.util.protobuf

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.internal.AbstractCollectionSerializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoType
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal const val WIRE_VARINT = 0
internal const val WIRE_I64 = 1
internal const val WIRE_SIZE_DELIMITED = 2
internal const val WIRE_I32 = 5

/**
 * Pool slots returned by the reader when the decoder finishes. They tell
 * [releaseDecoder] which stack to pop from, keeping the two pools
 * ([messageDecoders], [repeatedDecoders]) separate so a decoder can never be
 * handed out for the wrong structural role.
 */
internal const val POOL_NONE = 0
internal const val POOL_MESSAGE = 1
internal const val POOL_REPEATED = 2

/**
 * A cursor over a [BufferedSource] that understands the protobuf tag/value
 * grammar.
 *
 * Responsibilities:
 *  - Decoding varints (with a fast path for single-byte values).
 *  - Tracking the current `(fieldNumber, wireType)` after [readTag].
 *  - Enforcing message boundaries via a stack of remaining-length limits,
 *    so a malformed or truncated message fails cleanly rather than reading
 *    into the parent's data.
 *  - Handing out reusable [ProtobufSourceDecoder] instances for nested
 *    messages and repeated fields (bounded allocation for long lists).
 *
 * Not thread-safe, one reader per decode call.
 */
@PublishedApi
internal class ProtobufSourceReader(
    private val source: BufferedSource,
    private var remaining: Long = -1L,
) {

    /** Field number of the last [readTag] call, or `-1` on EOF. */
    var currentFieldNumber: Int = -1
        private set

    /** Wire type of the last [readTag] call, or `-1` on EOF. */
    var currentWireType: Int = -1
        private set

    // A single slot of "peeked" state. [readTag] writes the *previous* tag here
    // and consumes any pending push-back, so callers can look one tag ahead
    // (needed for repeated fields that stop at a foreign tag) and put it back
    // without seeking the underlying source.
    private var pushedBack = false
    private var pushBackHeader = 0

    // Per-nesting state. Sized 16 and doubled on demand; realistically deep
    // enough that the extra allocations never happen.
    private var depth = -1
    private var limits = LongArray(16)
    private var savedHeaders = IntArray(16)
    private var savedPushed = BooleanArray(16)
    private var savedIds = IntArray(16)
    private var savedTypes = IntArray(16)

    private val bounded: Boolean get() = remaining >= 0L

    /** Reduces the caller-visible byte budget and rejects overflow. */
    private fun consume(byteCount: Long) {
        if (bounded) {
            remaining -= byteCount
            if (remaining < 0) throw IOException("Field of field $currentFieldNumber runs past the enclosing message")
        }
    }

    private fun isExhausted(): Boolean = if (bounded) remaining <= 0L else source.exhausted()

    /**
     * Reads the next tag and updates [currentFieldNumber]/[currentWireType].
     * Returns the field number, or `-1` at end of message.
     *
     * If a tag was previously pushed back, it's returned instead of consuming
     * more bytes, a normal read is still attempted for the following call.
     */
    fun readTag(): Int {
        if (pushedBack) {
            pushedBack = false
            val previousHeader = (currentFieldNumber shl 3) or currentWireType
            val result = applyHeader(pushBackHeader)
            pushBackHeader = previousHeader
            return result
        }
        pushBackHeader = (currentFieldNumber shl 3) or currentWireType
        if (isExhausted()) return applyHeader(-1)
        return applyHeader(readVarint().toInt())
    }

    private fun applyHeader(header: Int): Int {
        if (header == -1) {
            currentFieldNumber = -1
            currentWireType = -1
            return -1
        }
        currentFieldNumber = header ushr 3
        currentWireType = header and 0x7
        return currentFieldNumber
    }

    /** Puts the current tag back so the next [readTag] returns it. */
    fun pushBackTag() {
        pushedBack = true
        val nextHeader = (currentFieldNumber shl 3) or currentWireType
        applyHeader(pushBackHeader)
        pushBackHeader = nextHeader
    }

    /**
     * Reads a varint-encoded unsigned 64-bit value.
     *
     * Fast path: if the first byte's high bit is clear, it's the whole value.
     * Otherwise, fall into [readVarintSlow] and continue accumulating 7-bit
     * groups. Protobuf allows up to 10 bytes (70 bits), and we cap shifts at
     * 63 to reject clearly malformed input.
     */
    fun readVarint(): Long {
        val first = source.readByte().toInt()
        if (first >= 0) {
            consume(1)
            return first.toLong()
        }
        return readVarintSlow(first)
    }

    private fun readVarintSlow(first: Int): Long {
        var result = (first and 0x7F).toLong()
        var shift = 7
        var byteCount = 1
        while (true) {
            val b = source.readByte().toInt()
            byteCount++
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b >= 0) break
            shift += 7
            if (shift >= 64) throw IOException("Malformed varint")
        }
        consume(byteCount.toLong())
        return result
    }

    /** Reads a varint length prefix and validates it against the current limit. */
    private fun readLength(): Long {
        val length = readVarint()
        if (length < 0) throw IOException("Unexpected negative length: $length")
        if (bounded && length > remaining) throw IOException("Length $length runs past the enclosing message ($remaining bytes left)")
        return length
    }

    /** Skips the payload of an unknown field based on its wire type. */
    fun skipValue() {
        when (currentWireType) {
            WIRE_VARINT -> readVarint()
            WIRE_I64 -> { source.skip(8); consume(8) }
            WIRE_SIZE_DELIMITED -> {
                val length = readLength()
                source.skip(length)
                consume(length)
            }
            WIRE_I32 -> { source.skip(4); consume(4) }
            else -> throw IOException("Unsupported start group or end group wire type: $currentWireType for field $currentFieldNumber")
        }
    }

    fun readLengthDelimitedUtf8(): String {
        val length = readLength()
        return source.readUtf8(length).also { consume(length) }
    }

    fun readLengthDelimitedBytes(): ByteArray {
        val length = readLength()
        return source.readByteArray(length).also { consume(length) }
    }

    fun readFixed32(): Int = source.readIntLe().also { consume(4) }
    fun readFixed64(): Long = source.readLongLe().also { consume(8) }
    fun exhausted(): Boolean = isExhausted()

    /**
     * Reads a length prefix, saves the current limit context, and makes the
     * nested message the active byte budget. The paired [popLimit] restores
     * the outer context after the nested message is consumed.
     *
     * The push-back state has to be saved/cleared too: a nested message
     * boundaries are their own tag stream, so any pending outer tag must not
     * leak into it.
     */
    fun pushLimit() {
        val length = readLength()
        consume(length)
        if (++depth == limits.size) grow()
        limits[depth] = remaining
        savedHeaders[depth] = pushBackHeader
        savedPushed[depth] = pushedBack
        savedIds[depth] = currentFieldNumber
        savedTypes[depth] = currentWireType
        remaining = length
        pushedBack = false
        pushBackHeader = 0
        currentFieldNumber = -1
        currentWireType = -1
    }

    /** Restores the previous limit context. Any unread bytes in the nested message are skipped. */
    fun popLimit() {
        if (remaining > 0) source.skip(remaining)
        remaining = limits[depth]
        pushBackHeader = savedHeaders[depth]
        pushedBack = savedPushed[depth]
        currentFieldNumber = savedIds[depth]
        currentWireType = savedTypes[depth]
        depth--
    }

    private fun grow() {
        val size = limits.size * 2
        limits = limits.copyOf(size)
        savedHeaders = savedHeaders.copyOf(size)
        savedPushed = savedPushed.copyOf(size)
        savedIds = savedIds.copyOf(size)
        savedTypes = savedTypes.copyOf(size)
    }

    // Decoder pools. Every decoder owns a [poolSlot] telling us which stack
    // it belongs to, so [releaseDecoder] decrements the right counter. This
    // gives a hot loop over repeated nested messages zero allocations after
    // the first iteration.
    private var messageDepth = 0
    private var messageDecoders = arrayOfNulls<ProtobufSourceDecoder>(8)
    private var repeatedDepth = 0
    private var repeatedDecoders = arrayOfNulls<RepeatedFieldDecoder>(8)

    /** Returns (reusing when possible) a decoder for a nested message field. */
    fun acquireMessageDecoder(descriptor: SerialDescriptor, info: ProtoFieldInfo): ProtobufSourceDecoder {
        if (messageDepth == messageDecoders.size) messageDecoders = messageDecoders.copyOf(messageDepth * 2)
        val decoder = messageDecoders[messageDepth]
            ?: ProtobufSourceDecoder(this, descriptor, info).also { messageDecoders[messageDepth] = it }
        messageDepth++
        decoder.resetMessage(descriptor, info)
        return decoder
    }

    /** Returns (reusing when possible) a decoder for a repeated field. */
    fun acquireRepeatedDecoder(
        descriptor: SerialDescriptor,
        info: ProtoFieldInfo,
        fieldNumber: Int,
        elementIntegerType: ProtoIntegerType,
    ): RepeatedFieldDecoder {
        if (repeatedDepth == repeatedDecoders.size) repeatedDecoders = repeatedDecoders.copyOf(repeatedDepth * 2)
        val decoder = repeatedDecoders[repeatedDepth]
            ?: RepeatedFieldDecoder(this, descriptor, info, fieldNumber, elementIntegerType)
                .also { repeatedDecoders[repeatedDepth] = it }
        repeatedDepth++
        decoder.resetRepeated(descriptor, info, fieldNumber, elementIntegerType)
        return decoder
    }

    /** Releases a decoder previously obtained from one of the two pools. */
    fun releaseDecoder(poolSlot: Int) {
        if (poolSlot == POOL_MESSAGE) messageDepth-- else repeatedDepth--
    }
}

// Per-element rescue classification. Used to tell [decodeElementIndex] how to
// emit an absent field: null for a nullable, an empty composite for a list,
// and nothing for a required/non-nullable scalar (which should then throw).
private const val RESCUE_NONE: Byte = 0
private const val RESCUE_NULLABLE: Byte = 1
private const val RESCUE_LIST: Byte = 2

/**
 * Precomputed per-descriptor metadata used at decode time.
 *
 * Cached keyed by [SerialDescriptor] because descriptors are canonical per
 * serializer, and rebuilding these tables per call would be wasteful.
 *
 * Tables:
 *  - [protoIds]      : element index -> protobuf field number (defaults to idx+1).
 *  - [integerTypes]  : element index -> zig-zag/fixed hint from `@ProtoType`.
 *  - [rescueKinds]   : element index -> [RESCUE_NULLABLE]/[RESCUE_LIST]/[RESCUE_NONE].
 *  - [idToIndex]     : field number -> element index for fast tag dispatch;
 *                      `null` when ids exceed [MAX_LOOKUP_ID] and we fall back
 *                      to a linear scan.
 *  - [elementInfo]   : lazily-populated per-element metadata for nested types.
 *  - [elementPackable] : true when this is a LIST whose element wire type can be
 *                        packed, i.e. [beginStructure] should use a packed decoder
 *                        when the wire type is SIZE_DELIMITED.
 */
@PublishedApi
internal class ProtoFieldInfo(descriptor: SerialDescriptor) {
    val protoIds = IntArray(descriptor.elementsCount)
    val integerTypes = Array(descriptor.elementsCount) { ProtoIntegerType.DEFAULT }
    val rescueKinds = ByteArray(descriptor.elementsCount)
    val hasRescuable: Boolean
    val useMask = descriptor.elementsCount <= 64
    val idToIndex: IntArray?

    private val elementInfo = arrayOfNulls<ProtoFieldInfo>(descriptor.elementsCount)

    val elementPackable: Boolean =
        descriptor.kind == StructureKind.LIST &&
                descriptor.elementsCount > 0 &&
                descriptor.getElementDescriptor(0).isPackedOnWire

    init {
        var rescuable = false
        var maxId = 0
        for (i in 0 until descriptor.elementsCount) {
            var id = i + 1
            val annotations = descriptor.getElementAnnotations(i)
            for (j in annotations.indices) {
                when (val annotation = annotations[j]) {
                    is ProtoNumber -> id = annotation.number
                    is ProtoType -> integerTypes[i] = annotation.type
                }
            }
            protoIds[i] = id
            if (id > maxId) maxId = id
            val element = descriptor.getElementDescriptor(i)
            rescueKinds[i] = when {
                element.isNullable -> RESCUE_NULLABLE
                element.kind == StructureKind.LIST -> RESCUE_LIST
                else -> RESCUE_NONE
            }
            if (rescueKinds[i] != RESCUE_NONE) rescuable = true
        }
        hasRescuable = rescuable
        // Small descriptors get an O(1) id->index table. Large ones (or sparse ids)
        // fall back to a linear scan in [indexOfProtoId] to avoid an
        // oversized array for one hot field id.
        idToIndex = if (maxId in 1..MAX_LOOKUP_ID) {
            IntArray(maxId + 1) { -1 }.also { table ->
                for (i in protoIds.indices) table[protoIds[i]] = i
            }
        } else null
    }

    fun elementInfo(index: Int, elementDescriptor: SerialDescriptor): ProtoFieldInfo =
        elementInfo[index] ?: of(elementDescriptor).also { elementInfo[index] = it }

    companion object {
        // a message with a field numbered beyond this falls back to a scan rather than a big table
        private const val MAX_LOOKUP_ID = 512

        private val cache = ConcurrentHashMap<SerialDescriptor, ProtoFieldInfo>()

        fun of(descriptor: SerialDescriptor): ProtoFieldInfo = cache.computeIfAbsent(descriptor, ::ProtoFieldInfo)
    }
}

/**
 * AbstractDecoder over a [ProtobufSourceReader].
 *
 * Lifecycle:
 *  1. [decodeSerializableValue] is called once for the root, [pendingRoot]
 *     tells us not to expect a wire tag for the outer CLASS structure.
 *  2. For every element, [decodeElementIndex] scans tags until it recognizes
 *     one (or EOF), reporting the element index to the generated serializer.
 *  3. For nested messages, [beginStructure] pushes a limit on the reader and
 *     asks the reader for a pooled child decoder.
 *  4. [endStructure] pops the limit and releases the child.
 *
 * Absent fields:
 *   The generated serializer calls [decodeElementIndex] until it returns
 *   [DECODE_DONE], then it fills absent fields with defaults. We emulate the
 *   "field is absent" signal by returning the index of a not-yet-seen element
 *   and either flagging it as absent-null ([decodeNotNullMark] returns false)
 *   or setting [currentFieldNumber] so the value read path errors if it's
 *   actually consulted. This is exactly how nullable/empty fields end up
 *   null/empty without needing to replay any bytes.
 */
@PublishedApi
internal open class ProtobufSourceDecoder(
    protected val reader: ProtobufSourceReader,
    protected var descriptor: SerialDescriptor,
    private var info: ProtoFieldInfo = ProtoFieldInfo.of(descriptor),
    private var ownsLimit: Boolean = false,
    private var pendingRoot: Boolean = false,
) : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    private var poolSlot = POOL_NONE
    protected var currentFieldNumber: Int = -1
        private set
    protected var currentElementIndex: Int = -1
    protected var currentIntegerType: ProtoIntegerType = ProtoIntegerType.DEFAULT

    // Subclasses (packed elements) don't validate wire types because there is
    // no per-element tag to check.
    protected open val checksWireType: Boolean get() = true

    // Bitmask when the descriptor is small enough, array otherwise. Chosen once
    // at construction based on `info.useMask`.
    private var seenMask = 0L
    private var seenArray: BooleanArray? = null
    private var pendingAbsentNull = false

    private fun markSeen(index: Int) {
        if (info.useMask) seenMask = seenMask or (1L shl index)
        else (seenArray ?: BooleanArray(descriptor.elementsCount).also { seenArray = it })[index] = true
    }

    private fun wasSeen(index: Int): Boolean =
        if (info.useMask) (seenMask and (1L shl index)) != 0L else seenArray?.get(index) == true

    /**
     * Called when the tag stream is exhausted but the descriptor still has
     * rescuable elements we haven't seen. Returns the index of the next one
     * and marks it seen so a second call advances further.
     *
     * Nullable         sets [pendingAbsentNull] so [decodeNotNullMark] returns false.
     *
     * List             sets [currentFieldNumber] to the element's id, the list
     *                  serializer will call [beginStructure] for an empty list and
     *                  observe the reader at EOF, producing zero elements.
     */
    private fun nextAbsentOptionalIndex(): Int {
        if (!info.hasRescuable) return CompositeDecoder.DECODE_DONE
        val kinds = info.rescueKinds
        for (i in kinds.indices) {
            if (kinds[i] == RESCUE_NONE || wasSeen(i)) continue
            markSeen(i)
            if (kinds[i] == RESCUE_NULLABLE) {
                pendingAbsentNull = true
            } else {
                currentFieldNumber = info.protoIds[i]
                currentIntegerType = info.integerTypes[i]
            }
            return i
        }
        return CompositeDecoder.DECODE_DONE
    }

    override fun decodeNotNullMark(): Boolean {
        if (pendingAbsentNull) {
            pendingAbsentNull = false
            return false
        }
        return true
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val table = info.idToIndex
        while (true) {
            val fieldNumber = reader.readTag()
            if (fieldNumber == -1) return nextAbsentOptionalIndex()
            if (fieldNumber == 0) throw IOException("0 is not a valid protobuf field number, the input may be corrupted")
            val index = if (table != null) {
                if (fieldNumber < table.size) table[fieldNumber] else -1
            } else indexOfProtoId(fieldNumber)
            if (index == -1) {
                // Unknown field, skip and keep scanning. This is what makes the
                // decoder forward-compatible with schema evolution.
                reader.skipValue()
                continue
            }
            currentFieldNumber = fieldNumber
            currentElementIndex = index
            currentIntegerType = info.integerTypes[index]
            markSeen(index)
            return index
        }
    }

    private fun indexOfProtoId(fieldNumber: Int): Int {
        val ids = info.protoIds
        for (i in ids.indices) if (ids[i] == fieldNumber) return i
        return -1
    }

    private fun expect(wireType: Int) {
        if (checksWireType && reader.currentWireType != wireType) {
            throw IOException("Expected wire type $wireType for field ${reader.currentFieldNumber}, but found ${reader.currentWireType}")
        }
    }

    /**
     * Integer read path shared by Int/Long/Byte/Short/Char.
     * Dispatches on `@ProtoType`:
     *  - DEFAULT       raw varint (u64 semantics, truncated to the target type).
     *  - SIGNED        zig-zag varint (reverse transform of the encode side).
     *  - FIXED         fixed32/fixed64 little-endian.
     */
    private fun readIntValue(): Int = when (currentIntegerType) {
        ProtoIntegerType.DEFAULT -> { expect(WIRE_VARINT); reader.readVarint().toInt() }
        ProtoIntegerType.SIGNED -> {
            expect(WIRE_VARINT)
            val raw = reader.readVarint().toInt()
            (raw ushr 1) xor -(raw and 1)
        }
        ProtoIntegerType.FIXED -> { expect(WIRE_I32); reader.readFixed32() }
    }

    private fun readLongValue(): Long = when (currentIntegerType) {
        ProtoIntegerType.DEFAULT -> { expect(WIRE_VARINT); reader.readVarint() }
        ProtoIntegerType.SIGNED -> {
            expect(WIRE_VARINT)
            val raw = reader.readVarint()
            (raw ushr 1) xor -(raw and 1L)
        }
        ProtoIntegerType.FIXED -> { expect(WIRE_I64); reader.readFixed64() }
    }

    override fun decodeBoolean(): Boolean {
        expect(WIRE_VARINT)
        return reader.readVarint() != 0L
    }

    override fun decodeInt(): Int = readIntValue()
    override fun decodeLong(): Long = readLongValue()
    override fun decodeByte(): Byte = readIntValue().toByte()
    override fun decodeShort(): Short = readIntValue().toShort()
    override fun decodeChar(): Char = readIntValue().toChar()

    override fun decodeFloat(): Float {
        expect(WIRE_I32)
        return Float.fromBits(reader.readFixed32())
    }

    override fun decodeDouble(): Double {
        expect(WIRE_I64)
        return Double.fromBits(reader.readFixed64())
    }

    override fun decodeString(): String {
        expect(WIRE_SIZE_DELIMITED)
        return reader.readLengthDelimitedUtf8()
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        expect(WIRE_VARINT)
        return reader.readVarint().toInt()
    }

    /**
     * Two special cases handled here instead of the stock decoder:
     *
     *  1. [ByteArray] its default serializer would go through a LIST of
     *     bytes, but on the wire bytes are a single SIZE_DELIMITED record.
     *     Concatenate when [previousValue] is non-null so a bytes field split
     *     across multiple tags (legal in protobuf) reassembles correctly.
     *
     *  2. Any [AbstractCollectionSerializer], a repeated field may appear
     *     more than once in a message, split around other fields, so later
     *     occurrences extend the list built so far instead of replacing it.
     */
    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>, previousValue: T?): T = when {
        deserializer === ByteArraySerializer() -> {
            expect(WIRE_SIZE_DELIMITED)
            val bytes = reader.readLengthDelimitedBytes()
            (if (previousValue == null) bytes else (previousValue as ByteArray) + bytes) as T
        }
        deserializer is AbstractCollectionSerializer<*, *, *> ->
            (deserializer as AbstractCollectionSerializer<*, T, *>).merge(this, previousValue)
        else -> super.decodeSerializableValue(deserializer, previousValue)
    }

    private fun infoFor(descriptor: SerialDescriptor): ProtoFieldInfo =
        if (currentElementIndex >= 0) info.elementInfo(currentElementIndex, descriptor) else ProtoFieldInfo.of(descriptor)

    /**
     * Resets this decoder for reuse out of a pool. Called by [ProtobufSourceReader]
     * right before handing it back to a [beginStructure] caller.
     */
    protected fun reset(descriptor: SerialDescriptor, info: ProtoFieldInfo, ownsLimit: Boolean, poolSlot: Int) {
        this.descriptor = descriptor
        this.info = info
        this.ownsLimit = ownsLimit
        this.poolSlot = poolSlot
        pendingRoot = false
        currentFieldNumber = -1
        currentElementIndex = -1
        currentIntegerType = ProtoIntegerType.DEFAULT
        seenMask = 0L
        seenArray = null
        pendingAbsentNull = false
    }

    internal fun resetMessage(descriptor: SerialDescriptor, info: ProtoFieldInfo) =
        reset(descriptor, info, ownsLimit = true, poolSlot = POOL_MESSAGE)

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = when (descriptor.kind) {
        StructureKind.LIST -> {
            val listInfo = infoFor(descriptor)
            if (reader.currentWireType == WIRE_SIZE_DELIMITED && listInfo.elementPackable) {
                // Proto3 packed representation: a single length-delimited blob
                // containing concatenated values with no per-element tags.
                reader.pushLimit()
                PackedFieldDecoder(reader, descriptor, listInfo, currentIntegerType)
            } else {
                // Repeated on wire, each element carries its own tag with the
                // same field number, the reader's push-back lets the repeated
                // decoder stop at the first foreign tag and hand it back.
                reader.acquireRepeatedDecoder(descriptor, listInfo, currentFieldNumber, currentIntegerType)
            }
        }
        StructureKind.CLASS, StructureKind.OBJECT -> if (pendingRoot) {
            // Top-level message, no tag for it, we're already positioned at
            // the first field.
            pendingRoot = false
            this
        } else {
            expect(WIRE_SIZE_DELIMITED)
            val nestedInfo = infoFor(descriptor)
            reader.pushLimit()
            reader.acquireMessageDecoder(descriptor, nestedInfo)
        }
        else -> throw IOException("Unsupported structure kind ${descriptor.kind} in ${descriptor.serialName}")
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        if (ownsLimit) reader.popLimit()
        if (poolSlot != POOL_NONE) {
            reader.releaseDecoder(poolSlot)
            poolSlot = POOL_NONE
        }
    }
}

/**
 * Decoder for a repeated field with per-element tags.
 *
 * The first element is already positioned by the parent's [readTag], subsequent
 * elements are read via [readTag] and compared to the repeated field's
 * [fieldNumber]. The first foreign tag is pushed back so the parent picks it up
 * on its next scan.
 */
internal class RepeatedFieldDecoder(
    reader: ProtobufSourceReader,
    descriptor: SerialDescriptor,
    info: ProtoFieldInfo,
    private var fieldNumber: Int,
    elementIntegerType: ProtoIntegerType,
) : ProtobufSourceDecoder(reader, descriptor, info, ownsLimit = false) {
    private var index = -1

    init {
        currentIntegerType = elementIntegerType
        // a list descriptor has exactly one element, so its info is always the one cached at 0
        currentElementIndex = 0
    }

    fun resetRepeated(
        descriptor: SerialDescriptor,
        info: ProtoFieldInfo,
        fieldNumber: Int,
        elementIntegerType: ProtoIntegerType,
    ) {
        reset(descriptor, info, ownsLimit = false, poolSlot = POOL_REPEATED)
        this.fieldNumber = fieldNumber
        index = -1
        currentIntegerType = elementIntegerType
        currentElementIndex = 0
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        // the first element's tag was already consumed by the parent to discover this is a list
        val tag = if (index == -1) reader.currentFieldNumber else reader.readTag()
        return if (tag == fieldNumber) {
            ++index
        } else {
            reader.pushBackTag()
            CompositeDecoder.DECODE_DONE
        }
    }
}

/**
 * Decoder for the packed representation of a repeated numeric field: the
 * length of the blob was already pushed onto the reader's limit stack, so
 * [decodeElementIndex] just watches for exhaustion.
 *
 * No wire-type validation is performed per element (there are no tags).
 */
private class PackedFieldDecoder(
    reader: ProtobufSourceReader,
    descriptor: SerialDescriptor,
    info: ProtoFieldInfo,
    elementIntegerType: ProtoIntegerType,
) : ProtobufSourceDecoder(reader, descriptor, info, ownsLimit = true) {
    private var index = -1

    init { currentIntegerType = elementIntegerType }

    override val checksWireType: Boolean get() = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (reader.exhausted()) CompositeDecoder.DECODE_DONE else ++index
}

/**
 * True when this descriptor can appear in a packed length-delimited blob on
 * the wire (proto3 packs everything except strings and messages).
 */
internal val SerialDescriptor.isPackedOnWire: Boolean
    get() = when {
        isInline -> elementsCount == 1 && getElementDescriptor(0).isPackedOnWire
        kind == SerialKind.ENUM -> true
        kind is PrimitiveKind -> kind != PrimitiveKind.STRING
        else -> false
    }

/** True when this descriptor should be written packed by our encoder. */
internal val SerialDescriptor.isPackedWhenWritten: Boolean
    get() = when {
        isInline -> elementsCount == 1 && getElementDescriptor(0).isPackedWhenWritten
        kind is PrimitiveKind -> kind != PrimitiveKind.STRING
        else -> false
    }