package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Explicit teacher projection. No account/profile/entity serialization. */
public final class InstitutionalAttemptReview {
    private InstitutionalAttemptReview() {}
    public record Command(@NotNull UUID operationId) {}
    public record Grade(@NotNull UUID operationId, @NotNull @Min(0) Integer score,
                        @Size(max=2000) String feedback) {}
    public record Adjustment(@NotNull UUID operationId,
            @NotNull @DecimalMin("-20") @DecimalMax("20") BigDecimal amount,
            @NotBlank @Size(max=500) String reason) {}
    public record Feedback(@NotNull UUID operationId, @Size(max=1500) String overallFeedback) {}
    public record Answer(Long answerId,long questionId,String questionText,int maxPoints,String expectedAnswer,
            String answerText,boolean reviewed,Integer pointsAwarded,String teacherFeedback,
            Long reviewedByAccountId,LocalDateTime reviewedAt) {}
    public record AdjustmentDetail(long id,BigDecimal amount,String type,String reason,boolean active,
            long createdByAccountId,LocalDateTime createdAt) {}
    public record Review(long attemptId,long recipientAccountId,String status,Integer score,Integer maxScore,
            BigDecimal finalScore,String overallFeedback,boolean gradeClosed,Long gradeClosedByAccountId,
            LocalDateTime gradeClosedAt,LocalDateTime submittedAt,LocalDateTime gradedAt,
            List<Answer> openAnswers,List<AdjustmentDetail> adjustments,Instant observedAt) {}
    public record Receipt(UUID operationId,long attemptId,Long childId,String status,boolean gradeClosed) {}
}
