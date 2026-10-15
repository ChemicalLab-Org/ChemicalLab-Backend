package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class InstitutionalAnswerSave {
    private InstitutionalAnswerSave() {}
    public record Command(@NotNull UUID operationId, @NotNull @Positive Long questionId,
                          @Positive Long selectedOptionId, @Size(max=3000) String answerText) {}
    /** Explicit allowlist. A receipt acknowledges a save; it never exposes correction. */
    public record Saved(UUID operationId,long attemptId,long questionId,Long selectedOptionId,String answerText,
                        int currentQuestionIndex,Instant savedAt) {}
}
