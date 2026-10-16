package com.morales.chemicallab.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Shared academic arithmetic for the legacy and published-revision engines. */
final class EvaluationGradeMath {
    private EvaluationGradeMath() {}
    static BigDecimal base(Integer score, Integer maximum) {
        return score == null || maximum == null || maximum == 0
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.valueOf(score).multiply(BigDecimal.valueOf(20))
                .divide(BigDecimal.valueOf(maximum), 2, RoundingMode.HALF_UP);
    }
    static BigDecimal finalScore(Integer score, Integer maximum, BigDecimal adjustments) {
        return base(score, maximum).add(adjustments.setScale(2, RoundingMode.HALF_UP))
            .max(BigDecimal.ZERO).min(BigDecimal.valueOf(20)).setScale(2, RoundingMode.HALF_UP);
    }
    static int awarded(boolean open, boolean reviewed, Integer manual, boolean correct, int maximum) {
        return open ? reviewed && manual != null ? manual : 0 : correct ? maximum : 0;
    }
}
