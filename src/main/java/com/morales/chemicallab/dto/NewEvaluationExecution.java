package com.morales.chemicallab.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** NEW_V1 projections never serialize the academic aggregate or its private JSON. */
public final class NewEvaluationExecution {
    private NewEvaluationExecution() {}
    public record Command(@NotNull UUID operationId) {}
    public record Answer(@NotNull @Positive Long revisionId, @NotNull @Positive Long questionId,
                         @Positive Long selectedOptionId, @Size(max=3000) String answerText) {}
    public record Save(@NotNull UUID operationId,@NotNull @Valid Answer answer) {}
    public record Submit(@NotNull UUID operationId,@Size(max=200) List<@NotNull @Valid Answer> answers) {}
    public record Receipt(UUID operationId,long attemptId,Long childId,String status,int attemptNumber,
                          boolean gradeClosed,Instant startedAt,Instant submittedAt) {}
    public record Scope(String engine,long institutionId,long classId,long evaluationId,long deliveryId,long revisionId) {}
    public record OwnAnswer(long revisionId,long questionId,Long selectedOptionId,String answerText) {}
    public record Attempt(Scope scope,long attemptId,String status,int attemptNumber,Instant startedAt,Instant deadline,
                          List<Long> questionOrder,int currentQuestionIndex,List<OwnAnswer> answers) {}
    public record TeacherAnswer(long answerId,long revisionId,long questionId,String questionText,String expectedAnswer,
                                int maxPoints,String answerText,boolean reviewed,Integer pointsAwarded,
                                String teacherFeedback,Long reviewedByAccountId,Instant reviewedAt) {}
    public record Adjustment(long id,BigDecimal amount,String reason,boolean active,long createdByAccountId,Instant createdAt) {}
    public record Review(Scope scope,long attemptId,long recipientAccountId,String status,Integer score,Integer maxScore,
                         BigDecimal finalScore,String overallFeedback,boolean gradeClosed,Long gradeClosedByAccountId,
                         Instant gradeClosedAt,Instant submittedAt,Instant gradedAt,List<TeacherAnswer> openAnswers,List<Adjustment> adjustments) {}
    public record CorrectedAnswer(long revisionId,long questionId,String questionText,String questionType,Long selectedOptionId,
                                  String answerText,Boolean correct,Integer pointsAwarded,String teacherFeedback,
                                  String correctOptionText,String explanation) {}
    public record Correction(Scope scope,long attemptId,Integer score,Integer maxScore,BigDecimal finalScore,
                             String overallFeedback,List<CorrectedAnswer> answers) {}
}
