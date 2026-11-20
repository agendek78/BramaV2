package com.agendek.bramav2.protocol

internal fun ByteArray.putLe(offset: Int, value: Long, size: Int) {
    for (i in 0 until size) this[offset + i] = (value ushr (8 * i)).toByte()
}

internal fun ByteArray.getLe(offset: Int, size: Int): Long {
    var v = 0L
    for (i in 0 until size) v = v or ((this[offset + i].toLong() and 0xff) shl (8 * i))
    return v
}

fun le64(value: Long): ByteArray = ByteArray(8).also { it.putLe(0, value, 8) }
