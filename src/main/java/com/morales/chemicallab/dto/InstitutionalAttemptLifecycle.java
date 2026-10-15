package com.morales.chemicallab.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class InstitutionalAttemptLifecycle {
    private InstitutionalAttemptLifecycle() {}
    public record Start(@NotNull UUID operationId) {}
    public record Answer(@NotNull @Positive Long questionId, @Positive Long selectedOptionId,
                         @Size(max=3000) String answerText) {}
    public record Submit(@NotNull UUID operationId, @Size(max=200) List<@NotNull @Valid Answer> answers) {}
    /** No score, correction, feedback or entity serialization. */
    public record Receipt(UUID operationId,long attemptId,String status,int attemptNumber,
                          LocalDateTime startedAt,LocalDateTime submittedAt) {}
}
