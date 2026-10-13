package com.morales.chemicallab.entity.institution;

/** Frozen legacy origin. Its closure/configuration snapshot never authorizes a live writer. */
public record EvaluationOpportunityGroup(long id,String origin,long evaluationId,int maxAttempts,
        String configurationJson,String assignmentsJson,boolean opportunitiesClosed,
        java.time.LocalDateTime evaluatedAtLocal,long reconciliationId) {}
