package com.agendek.bramav2.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Nazwa telefonu zapisana na sterowniku (client_management_design.md): do 20 B UTF-8, bez znaków sterujących
 * (C0, DEL, C1). Te same reguły stosuje firmware (`brama_client_name_valid`).
 */
object ClientName {
    const val MAX_BYTES = 20

    private fun isControl(cp: Int) = cp < 0x20 || cp in 0x7f..0x9f

    private fun utf8Length(cp: Int) = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    fun isValid(name: String): Boolean {
        if (name.codePoints().anyMatch(::isControl)) return false
        val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val bytes = try {
            encoder.encode(java.nio.CharBuffer.wrap(name))
        } catch (_: CharacterCodingException) {
            return false
        }
        return bytes.remaining() <= MAX_BYTES
    }

    /** Bajty nazwy do wysłania; [IllegalArgumentException] dla niepoprawnej nazwy. */
    fun encode(name: String): ByteArray {
        require(isValid(name)) { "Niepoprawna nazwa telefonu" }
        return name.toByteArray(Charsets.UTF_8)
    }

    /** Nazwa dopasowana do reguł: bez znaków sterujących i niesparowanych surogatów, przycięta do 20 B na granicy znaku. */
    fun truncate(name: String): String {
        val out = StringBuilder()
        var bytes = 0
        for (cp in name.codePoints().toArray()) {
            if (isControl(cp) || Character.isSurrogate(cp.toChar()) && cp < 0x10000) continue
            val len = utf8Length(cp)
            if (bytes + len > MAX_BYTES) break
            out.appendCodePoint(cp)
            bytes += len
        }
        return out.toString()
    }

    /** Ścisłe dekodowanie nazwy z sieci; [ProtocolException] dla złego UTF-8 albo znaku sterującego. */
    fun decode(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw ProtocolException("Nazwa telefonu nie jest poprawnym UTF-8")
        }
        if (text.codePoints().anyMatch(::isControl)) throw ProtocolException("Nazwa telefonu zawiera znak sterujący")
        return text
    }
}

/** Wpis listy telefonów (13.1, `CLIENT_LIST`): `client_id[16] || flags[1] || name_len[1] || name[20]` — 38 B. */
class ClientEntry(
    clientId: ByteArray,
    val name: String,
    val isSelf: Boolean,
    val isConnected: Boolean,
    val ownsRule: Boolean,
) {
    val clientId: ByteArray = clientId.copyOf()

    init {
        require(clientId.size == 16) { "client_id musi mieć 16 B" }
    }

    override fun equals(other: Any?) = other is ClientEntry && clientId.contentEquals(other.clientId) && name == other.name &&
        isSelf == other.isSelf && isConnected == other.isConnected && ownsRule == other.ownsRule

    override fun hashCode() = clientId.contentHashCode() * 31 + name.hashCode()

    override fun toString() = "ClientEntry(${clientId.toHex().take(8)}, \"$name\", self=$isSelf, connected=$isConnected, ownsRule=$ownsRule)"
}

/** `request_seq[8 LE] || count[1] ||` count × [ClientEntry] (13.1): odpowiedź na `LIST_CLIENTS`. */
data class ClientList(val requestSeq: Long, val entries: List<ClientEntry>) {
    fun encode(): ByteArray {
        require(entries.size <= MAX_CLIENTS) { "Za dużo wpisów" }
        val out = ByteArray(HEADER + entries.size * ENTRY_SIZE)
        out.putLe(0, requestSeq, 8)
        out[8] = entries.size.toByte()
        entries.forEachIndexed { i, e ->
            val o = HEADER + i * ENTRY_SIZE
            e.clientId.copyInto(out, o)
            out[o + 16] = ((if (e.isSelf) 1 else 0) or (if (e.isConnected) 2 else 0) or (if (e.ownsRule) 4 else 0)).toByte()
            val name = ClientName.encode(e.name)
            out[o + 17] = name.size.toByte()
            name.copyInto(out, o + 18)
        }
        return out
    }

    companion object {
        const val MAX_CLIENTS = 8
        const val HEADER = 9
        const val ENTRY_SIZE = 38

        fun decode(bytes: ByteArray): ClientList {
            if (bytes.size < HEADER) throw ProtocolException("CLIENT_LIST za krótki")
            val count = bytes[8].toInt() and 0xff
            if (count > MAX_CLIENTS) throw ProtocolException("CLIENT_LIST: za dużo wpisów ($count)")
            if (bytes.size != HEADER + count * ENTRY_SIZE) throw ProtocolException("CLIENT_LIST: rozmiar nie zgadza się z liczbą wpisów")
            val entries = List(count) { i ->
                val o = HEADER + i * ENTRY_SIZE
                val flags = bytes[o + 16].toInt() and 0xff
                val nameLen = bytes[o + 17].toInt() and 0xff
                if (nameLen > ClientName.MAX_BYTES) throw ProtocolException("CLIENT_LIST: nazwa dłuższa niż 20 B")
                for (k in nameLen until ClientName.MAX_BYTES) {
                    if (bytes[o + 18 + k].toInt() != 0) throw ProtocolException("CLIENT_LIST: niezerowe wypełnienie nazwy")
                }
                ClientEntry(
                    clientId = bytes.copyOfRange(o, o + 16),
                    name = ClientName.decode(bytes.copyOfRange(o + 18, o + 18 + nameLen)),
                    isSelf = flags and 1 != 0,
                    isConnected = flags and 2 != 0,
                    ownsRule = flags and 4 != 0,
                )
            }
            return ClientList(bytes.getLe(0, 8), entries)
        }
    }
}
