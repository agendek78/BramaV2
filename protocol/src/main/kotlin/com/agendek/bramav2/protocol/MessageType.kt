package com.agendek.bramav2.protocol

enum class Direction { C2S, S2C }

/** Rodzaj zabezpieczenia ramki, wyznaczony jednoznacznie przez typ (10.3). */
enum class TagKind(val length: Int) { HMAC(32), GCM(16), NONE(0) }

/** Faza, w której typ jest dozwolony (13.1): handshake sesji, rejestracja albo działająca sesja READY. */
enum class MessagePhase { HANDSHAKE, PAIR, SESSION }

/** Typy wiadomości protokołu v2 (11.1, 11.4, 12.1, 12.4, 13.1, 15.5). */
enum class MessageType(val code: Int, val direction: Direction, val tagKind: TagKind, val phase: MessagePhase) {
    OPEN(0x01, Direction.C2S, TagKind.GCM, MessagePhase.SESSION),
    CLOSE(0x02, Direction.C2S, TagKind.GCM, MessagePhase.SESSION),
    OPEN_PAIRING_WINDOW(0x03, Direction.C2S, TagKind.GCM, MessagePhase.SESSION),
    LIST_CLIENTS(0x04, Direction.C2S, TagKind.GCM, MessagePhase.SESSION),
    REMOVE_CLIENT(0x05, Direction.C2S, TagKind.GCM, MessagePhase.SESSION),
    SET_CLIENT_NAME(0x06, Direction.C2S, TagKind.GCM, MessagePhase.SESSION),
    HANDSHAKE_REJECT(0x0E, Direction.S2C, TagKind.NONE, MessagePhase.HANDSHAKE),
    SESSION_BEGIN(0x10, Direction.C2S, TagKind.HMAC, MessagePhase.HANDSHAKE),
    SESSION_CHALLENGE(0x11, Direction.S2C, TagKind.HMAC, MessagePhase.HANDSHAKE),
    SESSION_CONFIRM(0x12, Direction.C2S, TagKind.HMAC, MessagePhase.HANDSHAKE),
    SESSION_READY(0x13, Direction.S2C, TagKind.GCM, MessagePhase.HANDSHAKE),
    PAIR_BEGIN(0x20, Direction.C2S, TagKind.HMAC, MessagePhase.PAIR),
    PAIR_CHALLENGE(0x21, Direction.S2C, TagKind.HMAC, MessagePhase.PAIR),
    PAIR_CONFIRM(0x22, Direction.C2S, TagKind.HMAC, MessagePhase.PAIR),
    PAIR_GRANT(0x23, Direction.S2C, TagKind.GCM, MessagePhase.PAIR),
    PAIR_COMMIT(0x24, Direction.C2S, TagKind.GCM, MessagePhase.PAIR),
    PAIR_DONE(0x25, Direction.S2C, TagKind.GCM, MessagePhase.PAIR),
    PAKE_ROUND1(0x26, Direction.C2S, TagKind.NONE, MessagePhase.PAIR),
    PAKE_ROUND1_REPLY(0x27, Direction.S2C, TagKind.NONE, MessagePhase.PAIR),
    PAKE_ROUND2(0x28, Direction.C2S, TagKind.NONE, MessagePhase.PAIR),
    PAKE_ROUND2_REPLY(0x29, Direction.S2C, TagKind.NONE, MessagePhase.PAIR),
    PING(0x30, Direction.C2S, TagKind.GCM, MessagePhase.SESSION),
    PONG(0x31, Direction.S2C, TagKind.GCM, MessagePhase.SESSION),
    RULE_STATUS(0x40, Direction.S2C, TagKind.GCM, MessagePhase.SESSION),
    OTA_BEGIN(0x50, Direction.C2S, TagKind.GCM, MessagePhase.SESSION),
    OTA_DATA(0x51, Direction.C2S, TagKind.GCM, MessagePhase.SESSION),
    OTA_END(0x52, Direction.C2S, TagKind.GCM, MessagePhase.SESSION),
    OTA_ABORT(0x53, Direction.C2S, TagKind.GCM, MessagePhase.SESSION),
    COMMAND_ACK(0x80, Direction.S2C, TagKind.GCM, MessagePhase.SESSION),
    CLIENT_LIST(0x82, Direction.S2C, TagKind.GCM, MessagePhase.SESSION),
    OTA_ACK(0x81, Direction.S2C, TagKind.GCM, MessagePhase.SESSION);

    /** Domena funkcji MAC handshake (11.2), np. `BRAMA/2/PAIR_BEGIN`. */
    internal val macDomain: String get() = "BRAMA/2/$name"

    val isBegin: Boolean get() = this == SESSION_BEGIN || this == PAIR_BEGIN

    /** Rundy EC-JPAKE rejestracji PIN-em (11.4): `seq = 0`, bez tagu. */
    val isPake: Boolean get() = this == PAKE_ROUND1 || this == PAKE_ROUND1_REPLY || this == PAKE_ROUND2 || this == PAKE_ROUND2_REPLY

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Int): MessageType? = byCode[code]

        /** Nieznany typ traktuje się jak wiadomość AES-GCM z tagiem 16 B (13.3). */
        fun tagKindOf(code: Int): TagKind = fromCode(code)?.tagKind ?: TagKind.GCM
    }
}
