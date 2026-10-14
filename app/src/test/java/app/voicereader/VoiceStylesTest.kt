package app.voicereader

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceStylesTest {

    /** A voice.bin as generate_voices_bin.py writes it: n voices, style_ttl [n,2,3], style_dp [n,1,2]. */
    private fun bin(ttls: List<FloatArray>, dps: List<FloatArray>): ByteArray {
        val n = ttls.size.toLong()
        val b = ByteBuffer.allocate(48 + (ttls.sumOf { it.size } + dps.sumOf { it.size }) * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(n).putLong(2).putLong(3).putLong(n).putLong(1).putLong(2)
        ttls.forEach { t -> t.forEach { b.putFloat(it) } }
        dps.forEach { d -> d.forEach { b.putFloat(it) } }
        return b.array()
    }

    private fun ttl(v: Float) = FloatArray(6) { v + it }
    private fun dp(v: Float) = FloatArray(2) { v + it }

    @Test fun appendsAsTheNextSpeakerId() {
        val before = bin(listOf(ttl(0f), ttl(10f)), listOf(dp(0f), dp(10f)))
        val after = appendVoice(before, ttl(20f), dp(20f))

        assertEquals(3, readVoiceBinHeader(after).count)
        assertArrayEquals(bin(listOf(ttl(0f), ttl(10f), ttl(20f)), listOf(dp(0f), dp(10f), dp(20f))), after)
    }

    @Test fun rejectsAStyleOfAnotherShape() {
        val before = bin(listOf(ttl(0f)), listOf(dp(0f)))
        assertThrows(IllegalArgumentException::class.java) { appendVoice(before, FloatArray(5), dp(1f)) }
    }
}
