package com.wunderhand.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * How chairtime's JSON is read.
 *
 * Tolerant on purpose. An app in somebody's pocket is older than the server it
 * talks to, so a field it has not heard of is ignored, a field that has gone
 * missing reads as null where the type allows it, and an enum word it does not
 * know falls to that property's default rather than failing the whole screen.
 */
val ChairtimeJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/** Instants as ISO-8601, with or without milliseconds (`2026-09-16T11:30:00.000Z`). */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}
