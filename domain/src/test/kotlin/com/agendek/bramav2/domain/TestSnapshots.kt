package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase
import com.agendek.bramav2.protocol.OwnerRelation
import com.agendek.bramav2.protocol.RuleReason
import com.agendek.bramav2.protocol.RuleSnapshot

fun snapshot(
    phase: AutoClosePhase,
    owner: OwnerRelation = if (phase == AutoClosePhase.DISARMED) OwnerRelation.NONE else OwnerRelation.SELF,
    reason: RuleReason = RuleReason.NONE,
    remainingMs: Long = 0,
    revision: Long = 1,
) = RuleSnapshot(revision, phase, owner, reason, remainingMs)
