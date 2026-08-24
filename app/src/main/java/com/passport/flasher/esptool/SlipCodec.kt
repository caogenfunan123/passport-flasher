package com.passport.flasher.esptool

object SlipCodec {
    const val END = 0xc0.toByte()
    const val ESC = 0xdb.toByte()
    const val ESC_END = 0xdc.toByte()
    const val ESC_ESC = 0xdd.toByte()

    class FrameAccumulator {
        var partial: ByteArray? = null
        var isEscaping = false
    }

    fun encode(payload: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        out.add(END)
        for (b in payload) {
            when (b) {
                ESC -> { out.add(ESC); out.add(ESC_ESC) }
                END -> { out.add(ESC); out.add(ESC_END) }
                else -> out.add(b)
            }
        }
        out.add(END)
        return out.toByteArray()
    }

    fun decode(byte: Byte, acc: FrameAccumulator): ByteArray? {
        if (acc.partial == null) {
            if (byte == END) acc.partial = ByteArray(0)
            return null
        }
        if (acc.isEscaping) {
            acc.isEscaping = false
            when (byte) {
                ESC_END -> acc.partial = acc.partial!! + END
                ESC_ESC -> acc.partial = acc.partial!! + ESC
                else -> throw IllegalStateException("Invalid SLIP escape: 0xdb, 0x${byte.toUByte().toString(16)}")
            }
            return null
        }
        if (byte == ESC) { acc.isEscaping = true; return null }
        if (byte == END) {
            val frame = acc.partial!!
            acc.partial = ByteArray(0)
            return frame
        }
        acc.partial = acc.partial!! + byte
        return null
    }
}