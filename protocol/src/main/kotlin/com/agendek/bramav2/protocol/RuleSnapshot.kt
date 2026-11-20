package com.agendek.bramav2.protocol

enum class AutoClosePhase(val code: Int) { DISARMED(0), MONITORING(1), WAITING(2), BOOT_WAIT(3), BLOCKED(4), FAULT(5) }

/** Relacja właściciela reguły względem odbiorcy snapshotu. */
enum class OwnerRelation(val code: Int) { NONE(0), SELF(1), OTHER(2) }

enum class RuleReason(val code: Int) {
    NONE(0), LINK_LOST(1), REBOOT(2), INTERLOCK(3), IO_FAULT(4), STORAGE_FAILURE(5), BUSY_TIMEOUT(6)
}

/** Stan techniczny reguły autozamykania, 15 B (12.2). Nigdy nie opisuje położenia bramy. */
data class RuleSnapshot(
    val revision: Long,
    val phase: AutoClosePhase,
    val ownerRelation: OwnerRelation,
    val reason: RuleReason,
    val remainingMs: Long,
) {
    init {
        require(remainingMs in 0..0xffff_ffffL)
    }

    fun encode(): ByteArray {
        val b = ByteArray(SIZE)
        b.putLe(0, revision, 8)
        b[8] = phase.code.toByte()
        b[9] = ownerRelation.code.toByte()
        b[10] = reason.code.toByte()
        b.putLe(11, remainingMs, 4)
        return b
    }

    companion object {
        const val SIZE = 15

        fun decode(bytes: ByteArray): RuleSnapshot {
            if (bytes.size != SIZE) throw ProtocolException("RuleSnapshot musi mieć $SIZE B")
            val s = RuleSnapshot(
                revision = bytes.getLe(0, 8),
                phase = codeOf(AutoClosePhase.entries, bytes[8], AutoClosePhase::code, "phase"),
                ownerRelation = codeOf(OwnerRelation.entries, bytes[9], OwnerRelation::code, "owner_relation"),
                reason = codeOf(RuleReason.entries, bytes[10], RuleReason::code, "reason"),
                remainingMs = bytes.getLe(11, 4),
            )
            if (!s.isConsistent()) throw ProtocolException("Niespójny RuleSnapshot: $s")
            return s
        }

        private fun RuleSnapshot.isConsistent(): Boolean {
            val armed = ownerRelation != OwnerRelation.NONE
            val hasDeadline = phase == AutoClosePhase.WAITING || phase == AutoClosePhase.BOOT_WAIT
            if (!hasDeadline && remainingMs != 0L) return false
            return when (phase) {
                AutoClosePhase.DISARMED -> !armed && (reason == RuleReason.NONE || reason == RuleReason.REBOOT)
                AutoClosePhase.MONITORING -> armed && reason == RuleReason.NONE
                AutoClosePhase.WAITING -> armed && reason == RuleReason.LINK_LOST
                AutoClosePhase.BOOT_WAIT -> armed && reason == RuleReason.REBOOT
                AutoClosePhase.BLOCKED -> armed && reason == RuleReason.INTERLOCK
                AutoClosePhase.FAULT -> armed && reason in FAULT_REASONS
            }
        }

        private val FAULT_REASONS = setOf(RuleReason.IO_FAULT, RuleReason.BUSY_TIMEOUT, RuleReason.STORAGE_FAILURE)
    }
}

internal fun <T> codeOf(values: List<T>, byte: Byte, code: (T) -> Int, field: String): T {
    val c = byte.toInt() and 0xff
    return values.firstOrNull { code(it) == c } ?: throw ProtocolException("Nieznany kod $field: $c")
}
