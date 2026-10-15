package com.morales.chemicallab.dto;

import com.morales.chemicallab.service.EvaluationDeliveryAccessService.Contract;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** T12-B allowlists. No entity, JsonNode, snapshot or arbitrary map crosses REST. */
public final class InstitutionalEvaluationRead {
    private InstitutionalEvaluationRead() {}
    public record Scope(Contract contract, long institutionId, long classId, long evaluationId,
                        long deliveryId, long revisionId) {}
    public record Metadata(Scope scope, String projection, Instant observedAt) {}
    public record Option(long id, String optionText, int orderIndex) {}
    public record Question(long id, String questionText, String questionType, int points,
                           int orderIndex, boolean required, List<Option> options) {}
    public record Delivery(Scope scope, String title, String description, String instructions,
                           String topic, Integer timeLimitMinutes, boolean allowChemicalCalculator,
                           boolean allowPeriodicTable, boolean trackTabExit, String questionDisplayMode,
                           boolean randomizeQuestions, List<Question> questions, Instant observedAt) {}
    public record Answer(long questionId, Long selectedOptionId, String answerText) {}
    public record Attempt(Scope scope, long attemptId, String status, int attemptNumber,
                          List<Long> questionOrder, Integer currentQuestionIndex,
                          List<Answer> answers, Instant observedAt) {}
    // expectedAnswer/private criteria never belong to the student correction contract either.
    public record AnswerCorrection(long questionId, String questionText, String questionType,
                                   Long selectedOptionId, String answerText, Boolean correct,
                                   Integer pointsAwarded, String teacherFeedback,
                                   String correctOptionText, String explanation) {}
    public record Correction(Scope scope, long attemptId, Integer score, Integer maxScore,
                             BigDecimal finalScore, String overallFeedback,
                             List<AnswerCorrection> answers, Instant observedAt) {}
}
