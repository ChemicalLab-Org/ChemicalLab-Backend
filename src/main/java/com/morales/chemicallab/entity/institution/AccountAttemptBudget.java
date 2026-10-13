package com.morales.chemicallab.entity.institution;

/** Consumption reserved at rehearsal time, including conflicting/inactive/unassigned originals.
 * Legacy evaluation/student remains authoritative. sourceCurrent must accompany every observation.
 */
public record AccountAttemptBudget(long id,long groupId,long accountId,long usedCount,Long nextNumber,
        Long activeAttemptId,Status status,boolean sourceCurrent,String reconciliationStatus) {
    public enum Status { RECONCILED, CONFLICT }
}
