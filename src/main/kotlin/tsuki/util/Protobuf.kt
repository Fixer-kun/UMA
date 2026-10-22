@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package tsuki.util

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.internal.AbstractCollectionSerializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoOneOf
import kotlinx.serialization.protobuf.ProtoPacked
import kotlinx.serialization.protobuf.ProtoType
import kotlinx.serialization.serializer
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Source
import okio.buffer
import okio.utf8Size
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

// ===================== Public API surface =====================

val protoInstance: ProtoBuf = ProtoBuf { encodeDefaults = true }
val PROTOBUF_MEDIA_TYPE: MediaType = "application/protobuf".toMediaType()

inline fun <reified T> ByteArray.decodeProto(proto: ProtoBuf = protoInstance): T =
    proto.decodeFromByteArray<T>(this)

inline fun <reified T : Any> T.encodeProto(proto: ProtoBuf = protoInstance): ByteArray =
    proto.encodeToByteArray(this)

inline fun <reified T> BufferedSource.decodeProto(byteCount: Long = -1L): T =
    decodeProto(serializer<T>(), byteCount)

inline fun <reified T> BufferedSink.encodeProto(value: T, encodeDefaults: Boolean = false) =
    encodeProto(serializer<T>(), value, encodeDefaults)

inline fun <reified T> Response.parseAsProto(): T =
    use { it.body.source().decodeProto<T>() }

inline fun <reified T> Response.parseAsProto(transform: (BufferedSource) -> Source): T =
    use { transform(it.body.source()).buffer().decodeProto<T>() }

inline fun <reified T> ResponseBody.parseAsProto(): T =
    use { it.source().decodeProto<T>() }

inline fun <reified T : Any> T.toRequestBodyProto(
    mediaType: MediaType = PROTOBUF_MEDIA_TYPE,
    encodeDefaults: Boolean = false,
): RequestBody {
    val value = this
    val strategy = serializer<T>()
    return object : RequestBody() {
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = -1
        override fun writeTo(sink: BufferedSink) = sink.encodeProto(strategy, value, encodeDefaults)
    }
}

/** Decodes standard (padded, non-URL-safe) base64 into a proto message. */
inline fun <reified T> String.decodeProtoBase64(proto: ProtoBuf = protoInstance): T =
    Base64.getDecoder().decode(this).decodeProto(proto)

/** Encodes a proto message to standard (padded, non-URL-safe, single-line) base64. */
inline fun <reified T : Any> T.encodeProtoBase64(proto: ProtoBuf = protoInstance): String =
    Base64.getEncoder().encodeToString(encodeProto(proto))

@PublishedApi
internal fun <T> BufferedSource.decodeProto(
    strategy: DeserializationStrategy<T>,
    byteCount: Long,
): T = ProtobufSourceDecoder(
    ProtobufSourceReader(this, byteCount),
    strategy.descriptor,
    pendingRoot = true,
).decodeSerializableValue(strategy)

@PublishedApi
internal fun <T> BufferedSink.encodeProto(
    strategy: SerializationStrategy<T>,
    value: T,
    encodeDefaults: Boolean,
) = ProtobufSinkEncoder(
    ProtobufSinkWriter(this),
    strategy.descriptor,
    encodeDefaults,
    pendingRoot = true,
).encodeSerializableValue(strategy, value)

// ===================== Wire type constants =====================

internal const val WIRE_VARINT = 0
internal const val WIRE_I64 = 1
internal const val WIRE_SIZE_DELIMITED = 2
internal const val WIRE_I32 = 5

internal const val POOL_NONE = 0
internal const val POOL_MESSAGE = 1
internal const val POOL_REPEATED = 2

// ===================== Reader =====================

@PublishedApi
internal class ProtobufSourceReader(
    private val source: BufferedSource,
    private var remaining: Long = -1L,
) {

    var currentFieldNumber: Int = -1
        private set
    var currentWireType: Int = -1
        private set

    private var pushedBack = false
    private var pushBackHeader = 0

    private var depth = -1
    private var limits = LongArray(16)
    private var savedHeaders = IntArray(16)
    private var savedPushed = BooleanArray(16)
    private var savedIds = IntArray(16)
    private var savedTypes = IntArray(16)

    private val bounded: Boolean get() = remaining >= 0L

    private fun consume(byteCount: Long) {
        if (bounded) {
            remaining -= byteCount
            if (remaining < 0) throw IOException("Field of field $currentFieldNumber runs past the enclosing message")
        }
    }

    private fun isExhausted(): Boolean = if (bounded) remaining <= 0L else source.exhausted()

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

    fun pushBackTag() {
        pushedBack = true
        val nextHeader = (currentFieldNumber shl 3) or currentWireType
        applyHeader(pushBackHeader)
        pushBackHeader = nextHeader
    }

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

    private fun readLength(): Long {
        val length = readVarint()
        if (length < 0) throw IOException("Unexpected negative length: $length")
        if (bounded && length > remaining) throw IOException("Length $length runs past the enclosing message ($remaining bytes left)")
        return length
    }

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

    private var messageDepth = 0
    private var messageDecoders = arrayOfNulls<ProtobufSourceDecoder>(8)
    private var repeatedDepth = 0
    private var repeatedDecoders = arrayOfNulls<RepeatedFieldDecoder>(8)

    fun acquireMessageDecoder(descriptor: SerialDescriptor, info: ProtoFieldInfo): ProtobufSourceDecoder {
        if (messageDepth == messageDecoders.size) messageDecoders = messageDecoders.copyOf(messageDepth * 2)
        val decoder = messageDecoders[messageDepth]
            ?: ProtobufSourceDecoder(this, descriptor, info).also { messageDecoders[messageDepth] = it }
        messageDepth++
        decoder.resetMessage(descriptor, info)
        return decoder
    }

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

    fun releaseDecoder(poolSlot: Int) {
        if (poolSlot == POOL_MESSAGE) messageDepth-- else repeatedDepth--
    }
}

// ===================== Field info =====================

private const val RESCUE_NONE: Byte = 0
private const val RESCUE_NULLABLE: Byte = 1
private const val RESCUE_LIST: Byte = 2

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
        idToIndex = if (maxId in 1..MAX_LOOKUP_ID) {
            IntArray(maxId + 1) { -1 }.also { table ->
                for (i in protoIds.indices) table[protoIds[i]] = i
            }
        } else null
    }

    fun elementInfo(index: Int, elementDescriptor: SerialDescriptor): ProtoFieldInfo =
        elementInfo[index] ?: of(elementDescriptor).also { elementInfo[index] = it }

    companion object {
        private const val MAX_LOOKUP_ID = 512
        private val cache = ConcurrentHashMap<SerialDescriptor, ProtoFieldInfo>()
        fun of(descriptor: SerialDescriptor): ProtoFieldInfo = cache.computeIfAbsent(descriptor, ::ProtoFieldInfo)
    }
}

// ===================== Decoder =====================

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
    protected open val checksWireType: Boolean get() = true

    private var seenMask = 0L
    private var seenArray: BooleanArray? = null
    private var pendingAbsentNull = false

    private fun markSeen(index: Int) {
        if (info.useMask) seenMask = seenMask or (1L shl index)
        else (seenArray ?: BooleanArray(descriptor.elementsCount).also { seenArray = it })[index] = true
    }

    private fun wasSeen(index: Int): Boolean =
        if (info.useMask) (seenMask and (1L shl index)) != 0L else seenArray?.get(index) == true

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
                reader.pushLimit()
                PackedFieldDecoder(reader, descriptor, listInfo, currentIntegerType)
            } else {
                reader.acquireRepeatedDecoder(descriptor, listInfo, currentFieldNumber, currentIntegerType)
            }
        }
        StructureKind.CLASS, StructureKind.OBJECT -> if (pendingRoot) {
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
        val tag = if (index == -1) reader.currentFieldNumber else reader.readTag()
        return if (tag == fieldNumber) {
            ++index
        } else {
            reader.pushBackTag()
            CompositeDecoder.DECODE_DONE
        }
    }
}

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

internal val SerialDescriptor.isPackedOnWire: Boolean
    get() = when {
        isInline -> elementsCount == 1 && getElementDescriptor(0).isPackedOnWire
        kind == SerialKind.ENUM -> true
        kind is PrimitiveKind -> kind != PrimitiveKind.STRING
        else -> false
    }

internal val SerialDescriptor.isPackedWhenWritten: Boolean
    get() = when {
        isInline -> elementsCount == 1 && getElementDescriptor(0).isPackedWhenWritten
        kind is PrimitiveKind -> kind != PrimitiveKind.STRING
        else -> false
    }

// ===================== Writer =====================

@PublishedApi
internal class ProtobufSinkWriter(private val root: BufferedSink) {
    private var sink: BufferedSink = root
    private var depth = 0
    private var buffers = arrayOfNulls<Buffer>(8)
    private val varintScratch = ByteArray(16)

    fun beginMessage() {
        if (depth == buffers.size) buffers = buffers.copyOf(depth * 2)
        val buffer = buffers[depth] ?: Buffer().also { buffers[depth] = it }
        depth++
        sink = buffer
    }

    fun endMessage(fieldNumber: Int, omitIfEmpty: Boolean = false) {
        val message = sink as Buffer
        depth--
        sink = if (depth == 0) root else buffers[depth - 1]!!
        val size = message.size
        if (omitIfEmpty && size == 0L) return
        val scratch = varintScratch
        var count = putVarint(scratch, 0, (fieldNumber.toLong() shl 3) or WIRE_SIZE_DELIMITED.toLong())
        count = putVarint(scratch, count, size)
        sink.write(scratch, 0, count)
        sink.write(message, size)
    }

    private fun writeTag(fieldNumber: Int, wireType: Int) =
        writeVarint((fieldNumber.toLong() shl 3) or wireType.toLong())

    private fun putVarint(target: ByteArray, offset: Int, value: Long): Int {
        var v = value
        var at = offset
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                target[at++] = v.toByte()
                return at
            }
            target[at++] = ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
    }

    fun writeVarint(value: Long) {
        if (value and 0x7FL.inv() == 0L) {
            sink.writeByte(value.toInt())
            return
        }
        sink.write(varintScratch, 0, putVarint(varintScratch, 0, value))
    }

    fun writeVarint(fieldNumber: Int, value: Long) {
        writeTag(fieldNumber, WIRE_VARINT)
        writeVarint(value)
    }

    fun writeFixed32(bits: Int) = sink.writeIntLe(bits)
    fun writeFixed32(fieldNumber: Int, bits: Int) {
        writeTag(fieldNumber, WIRE_I32); sink.writeIntLe(bits)
    }
    fun writeFixed64(bits: Long) = sink.writeLongLe(bits)
    fun writeFixed64(fieldNumber: Int, bits: Long) {
        writeTag(fieldNumber, WIRE_I64); sink.writeLongLe(bits)
    }

    fun writeLengthDelimitedUtf8(fieldNumber: Int, value: String) {
        writeTag(fieldNumber, WIRE_SIZE_DELIMITED)
        writeVarint(value.utf8Size())
        sink.writeUtf8(value)
    }

    fun writeLengthDelimited(fieldNumber: Int, bytes: ByteArray) {
        writeTag(fieldNumber, WIRE_SIZE_DELIMITED)
        writeVarint(bytes.size.toLong())
        sink.write(bytes)
    }
}

private class ProtoEncodeInfo(descriptor: SerialDescriptor) {
    val protoIds = IntArray(descriptor.elementsCount)
    val integerTypes = Array(descriptor.elementsCount) { ProtoIntegerType.DEFAULT }
    val packed = BooleanArray(descriptor.elementsCount)
    val oneOf = BooleanArray(descriptor.elementsCount)

    init {
        for (i in 0 until descriptor.elementsCount) {
            var id = i + 1
            val annotations = descriptor.getElementAnnotations(i)
            for (j in annotations.indices) {
                when (val annotation = annotations[j]) {
                    is ProtoNumber -> id = annotation.number
                    is ProtoType -> integerTypes[i] = annotation.type
                    is ProtoPacked -> packed[i] = true
                    is ProtoOneOf -> oneOf[i] = true
                }
            }
            protoIds[i] = id
        }
    }

    companion object {
        private val cache = ConcurrentHashMap<SerialDescriptor, ProtoEncodeInfo>()
        fun of(descriptor: SerialDescriptor): ProtoEncodeInfo = cache.computeIfAbsent(descriptor, ::ProtoEncodeInfo)
    }
}

@PublishedApi
internal open class ProtobufSinkEncoder(
    protected val writer: ProtobufSinkWriter,
    descriptor: SerialDescriptor,
    protected val encodeDefaults: Boolean,
    private var pendingRoot: Boolean = false,
) : AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()
    override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean = encodeDefaults

    protected var currentFieldNumber: Int = -1
    protected var currentIntegerType: ProtoIntegerType = ProtoIntegerType.DEFAULT
    private var currentPacked = false
    private var pendingOneOf = false

    protected open val writesTags: Boolean get() = true

    private val encodeInfo = ProtoEncodeInfo.of(descriptor)

    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        if (encodeInfo.oneOf[index]) pendingOneOf = true
        else {
            currentFieldNumber = encodeInfo.protoIds[index]
            currentIntegerType = encodeInfo.integerTypes[index]
            currentPacked = encodeInfo.packed[index]
        }
        return true
    }

    private fun writeIntValue(value: Int) {
        when (currentIntegerType) {
            ProtoIntegerType.DEFAULT -> writeVarintValue(value.toLong())
            ProtoIntegerType.SIGNED -> writeVarintValue(((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL)
            ProtoIntegerType.FIXED -> if (writesTags) writer.writeFixed32(currentFieldNumber, value) else writer.writeFixed32(value)
        }
    }

    private fun writeLongValue(value: Long) {
        when (currentIntegerType) {
            ProtoIntegerType.DEFAULT -> writeVarintValue(value)
            ProtoIntegerType.SIGNED -> writeVarintValue((value shl 1) xor (value shr 63))
            ProtoIntegerType.FIXED -> if (writesTags) writer.writeFixed64(currentFieldNumber, value) else writer.writeFixed64(value)
        }
    }

    private fun writeVarintValue(value: Long) {
        if (writesTags) writer.writeVarint(currentFieldNumber, value) else writer.writeVarint(value)
    }

    override fun encodeBoolean(value: Boolean) = writeVarintValue(if (value) 1L else 0L)
    override fun encodeInt(value: Int) = writeIntValue(value)
    override fun encodeLong(value: Long) = writeLongValue(value)
    override fun encodeByte(value: Byte) = writeIntValue(value.toInt())
    override fun encodeShort(value: Short) = writeIntValue(value.toInt())
    override fun encodeChar(value: Char) = writeIntValue(value.code)
    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = writeVarintValue(index.toLong())

    override fun encodeFloat(value: Float) {
        if (writesTags) writer.writeFixed32(currentFieldNumber, value.toRawBits()) else writer.writeFixed32(value.toRawBits())
    }

    override fun encodeDouble(value: Double) {
        if (writesTags) writer.writeFixed64(currentFieldNumber, value.toRawBits()) else writer.writeFixed64(value.toRawBits())
    }

    override fun encodeNull() = Unit

    override fun encodeString(value: String) {
        if (!writesTags) throw IOException("Strings cannot appear in a packed field")
        writer.writeLengthDelimitedUtf8(currentFieldNumber, value)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        if (serializer === ByteArraySerializer()) {
            writer.writeLengthDelimited(currentFieldNumber, value as ByteArray)
        } else {
            super.encodeSerializableValue(serializer, value)
        }
    }

    override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder =
        if (currentPacked && descriptor.getElementDescriptor(0).isPackedWhenWritten) {
            writer.beginMessage()
            PackedFieldEncoder(writer, currentFieldNumber, descriptor, encodeDefaults, currentIntegerType)
        } else {
            RepeatedFieldEncoder(writer, currentFieldNumber, descriptor, encodeDefaults, currentIntegerType)
        }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        if (pendingOneOf) {
            pendingOneOf = false
            return OneOfWrapperEncoder(writer, descriptor, encodeDefaults)
        }
        return when (descriptor.kind) {
            StructureKind.LIST -> beginCollection(descriptor, 0)
            StructureKind.CLASS, StructureKind.OBJECT -> if (pendingRoot) {
                pendingRoot = false
                this
            } else {
                writer.beginMessage()
                NestedMessageEncoder(writer, currentFieldNumber, descriptor, encodeDefaults)
            }
            else -> throw IOException("Unsupported structure kind ${descriptor.kind} in ${descriptor.serialName}")
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {}
}

private class NestedMessageEncoder(
    writer: ProtobufSinkWriter,
    private val parentFieldNumber: Int,
    descriptor: SerialDescriptor,
    encodeDefaults: Boolean,
) : ProtobufSinkEncoder(writer, descriptor, encodeDefaults) {
    override fun endStructure(descriptor: SerialDescriptor) {
        writer.endMessage(parentFieldNumber)
    }
}

private class RepeatedFieldEncoder(
    writer: ProtobufSinkWriter,
    private val fieldNumber: Int,
    descriptor: SerialDescriptor,
    encodeDefaults: Boolean,
    private val elementIntegerType: ProtoIntegerType,
) : ProtobufSinkEncoder(writer, descriptor, encodeDefaults) {
    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        currentFieldNumber = fieldNumber
        currentIntegerType = elementIntegerType
        return true
    }
}

private class PackedFieldEncoder(
    writer: ProtobufSinkWriter,
    private val parentFieldNumber: Int,
    descriptor: SerialDescriptor,
    encodeDefaults: Boolean,
    private val elementIntegerType: ProtoIntegerType,
) : ProtobufSinkEncoder(writer, descriptor, encodeDefaults) {
    override val writesTags: Boolean get() = false

    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        currentIntegerType = elementIntegerType
        return true
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        writer.endMessage(parentFieldNumber, omitIfEmpty = true)
    }
}

private class OneOfWrapperEncoder(
    writer: ProtobufSinkWriter,
    descriptor: SerialDescriptor,
    encodeDefaults: Boolean,
) : ProtobufSinkEncoder(writer, descriptor, encodeDefaults) {
    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean = index != 0
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        ProtobufSinkEncoder(writer, descriptor, encodeDefaults)
}
