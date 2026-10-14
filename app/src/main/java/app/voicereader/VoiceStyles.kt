package app.voicereader

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * sherpa-onnx's Supertonic `voice.bin` (scripts/supertonic/generate_voices_bin.py): the int64 dims of style_ttl
 * [n, a, b] and of style_dp [n, c, d], little-endian, then every voice's style_ttl floats, then every style_dp.
 * A voice's speaker id (sid) is its position.
 */
class VoiceBinHeader(val count: Int, val ttl: Int, val dp: Int)

private const val HEADER_BYTES = 6 * 8

fun readVoiceBinHeader(bytes: ByteArray): VoiceBinHeader {
    require(bytes.size >= HEADER_BYTES) { "voice.bin too short" }
    val b = ByteBuffer.wrap(bytes, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    val n = b.long.toInt()
    val ttl = (b.long * b.long).toInt()
    require(b.long.toInt() == n) { "style_ttl and style_dp disagree on the voice count" }
    val dp = (b.long * b.long).toInt()
    return VoiceBinHeader(n, ttl, dp)
}

/** [bin] with one more voice at the end (sid = the old count). The style sizes must match the existing voices. */
fun appendVoice(bin: ByteArray, ttl: FloatArray, dp: FloatArray): ByteArray {
    val h = readVoiceBinHeader(bin)
    require(ttl.size == h.ttl && dp.size == h.dp) {
        "style sizes ${ttl.size}/${dp.size} don't match this pack's ${h.ttl}/${h.dp}"
    }
    val ttlBytes = h.count * h.ttl * 4
    val dpBytes = h.count * h.dp * 4
    require(bin.size == HEADER_BYTES + ttlBytes + dpBytes) { "voice.bin size doesn't match its header" }

    val header = ByteBuffer.wrap(bin, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    val dims = LongArray(6) { header.long }
    val out = ByteBuffer.allocate(bin.size + (ttl.size + dp.size) * 4).order(ByteOrder.LITTLE_ENDIAN)
    out.putLong(dims[0] + 1).putLong(dims[1]).putLong(dims[2])
    out.putLong(dims[3] + 1).putLong(dims[4]).putLong(dims[5])
    out.put(bin, HEADER_BYTES, ttlBytes)
    ttl.forEach { out.putFloat(it) }
    out.put(bin, HEADER_BYTES + ttlBytes, dpBytes)
    dp.forEach { out.putFloat(it) }
    return out.array()
}
