@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package tsuki.util

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.serializer
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.BufferedSource
import okio.Source
import okio.buffer
import tsuki.util.protobuf.ProtobufSinkEncoder
import tsuki.util.protobuf.ProtobufSinkWriter
import tsuki.util.protobuf.ProtobufSourceDecoder
import tsuki.util.protobuf.ProtobufSourceReader
import java.util.Base64

/** Default ProtoBuf instance, `encodeDefaults = true` matches the generated-serializer default. */
val protoInstance: ProtoBuf = ProtoBuf { encodeDefaults = true }

/** MIME used by [toRequestBodyProto]. matches what most protobuf endpoints expect. */
val PROTOBUF_MEDIA_TYPE: MediaType = "application/protobuf".toMediaType()

/**
 * Streaming decode, non-reified entry point.
 *
 * `@PublishedApi` is used so the public inline functions above can call into
 * this without leaking the internal [ProtobufSourceReader]/[ProtobufSourceDecoder]
 * types into user compilation units.
 */
@PublishedApi
internal fun <T> BufferedSource.decodeProto(
    strategy: DeserializationStrategy<T>,
    byteCount: Long,
): T = ProtobufSourceDecoder(
    ProtobufSourceReader(this, byteCount),
    strategy.descriptor,
    pendingRoot = true,
).decodeSerializableValue(strategy)

/** Streaming encode, non-reified entry point. Mirrors the decode side above. */
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

/** Decodes a complete protobuf message from [this] byte array. */
inline fun <reified T> ByteArray.decodeProto(proto: ProtoBuf = protoInstance): T =
    proto.decodeFromByteArray<T>(this)

/** Encodes [this] to a complete protobuf message. */
inline fun <reified T : Any> T.encodeProto(proto: ProtoBuf = protoInstance): ByteArray =
    proto.encodeToByteArray(this)

/**
 * Streaming decode: reads a full protobuf message from [this] source.
 *
 * @param byteCount optional known length. If `>= 0`, the message is treated as
 *   bounded to exactly that many bytes; if `-1`, end-of-message is determined
 *   by the source's own EOF.
 */
inline fun <reified T> BufferedSource.decodeProto(byteCount: Long = -1L): T =
    decodeProto(serializer<T>(), byteCount)

/** Streaming encode: writes a full protobuf message to [this] sink. */
inline fun <reified T> BufferedSink.encodeProto(value: T, encodeDefaults: Boolean = false) =
    encodeProto(serializer<T>(), value, encodeDefaults)

/** Reads [this] response body as a protobuf message and closes the response. */
inline fun <reified T> Response.parseAsProto(): T =
    use { it.body.source().decodeProto<T>() }

/**
 * Reads [this] response body through a transform (e.g. a decompressor) before
 * decoding and closes the response.
 */
inline fun <reified T> Response.parseAsProto(transform: (BufferedSource) -> Source): T =
    use { transform(it.body.source()).buffer().decodeProto<T>() }

/** Reads [this] body as a protobuf message and closes the body. */
inline fun <reified T> ResponseBody.parseAsProto(): T =
    use { it.source().decodeProto<T>() }

/**
 * Wraps [this] as a [RequestBody] that encodes it as protobuf.
 *
 * [contentLength] returns -1, so OkHttp will buffer to a temp file for chunked
 * uploads. If you know the encoded size ahead of time, override it for better
 * performance.
 */
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
