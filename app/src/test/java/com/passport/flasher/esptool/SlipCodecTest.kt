package com.passport.flasher.esptool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlipCodecTest {

    @Test
    fun encodeEncodesControlBytes() {
        val payload = byteArrayOf(
            0x00.toByte(), 0xc0.toByte(), 0xdb.toByte(), 0x01.toByte(),
        )
        val encoded = SlipCodec.encode(payload)
        assertEquals(0xc0.toByte(), encoded[0])
        assertEquals(0xc0.toByte(), encoded[encoded.size - 1])
        assertEquals(0xdb.toByte(), encoded[2])
        assertEquals(0xdc.toByte(), encoded[3])
        assertEquals(0xdb.toByte(), encoded[4])
        assertEquals(0xdd.toByte(), encoded[5])
    }

    @Test
    fun decodeRoundTrip() {
        val payload = byteArrayOf(0x01, 0xc0.toByte(), 0xdb.toByte(), 0x02)
        val encoded = SlipCodec.encode(payload)
        val acc = SlipCodec.FrameAccumulator()
        val decoded = mutableListOf<ByteArray>()
        for (b in encoded) {
            decoded.addNotNullOrEmpty(SlipCodec.decode(b, acc))
        }
        assertEquals(1, decoded.size)
        assertTrue(payload.contentEquals(decoded[0]))
    }

    @Test
    fun decodeStreamingAcrossCalls() {
        val payload = byteArrayOf(0x7f, 0x00)
        val encoded = SlipCodec.encode(payload)
        val acc = SlipCodec.FrameAccumulator()
        val decoded = mutableListOf<ByteArray>()
        for (b in encoded) {
            decoded.addNotNullOrEmpty(SlipCodec.decode(b, acc))
        }
        assertEquals(1, decoded.size)
        assertTrue(payload.contentEquals(decoded[0]))
    }

    @Test
    fun decodeMultipleFramesInOneStream() {
        val f1 = byteArrayOf(0x01, 0x02)
        val f2 = byteArrayOf(0x03)
        val stream = SlipCodec.encode(f1) + SlipCodec.encode(f2)
        val acc = SlipCodec.FrameAccumulator()
        val decoded = mutableListOf<ByteArray>()
        for (b in stream) {
            decoded.addNotNullOrEmpty(SlipCodec.decode(b, acc))
        }
        assertEquals(2, decoded.size)
        assertTrue(f1.contentEquals(decoded[0]))
        assertTrue(f2.contentEquals(decoded[1]))
    }

    @Test
    fun decodeSkipsEmptyFrames() {
        val stream = byteArrayOf(0xc0.toByte(), 0xc0.toByte(), 0x01, 0xc0.toByte(), 0xc0.toByte())
        val acc = SlipCodec.FrameAccumulator()
        val decoded = mutableListOf<ByteArray>()
        for (b in stream) {
            val frame = SlipCodec.decode(b, acc)
            if (frame != null) decoded.add(frame)
        }
        assertEquals(1, decoded.size)
        assertTrue(byteArrayOf(0x01).contentEquals(decoded[0]))
    }

    private fun MutableList<ByteArray>.addNotNullOrEmpty(decoded: ByteArray?) {
        if (decoded != null) add(decoded)
    }
}