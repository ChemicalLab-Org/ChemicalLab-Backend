package com.morales.chemicallab.config;

import com.morales.chemicallab.service.EvaluationAccessPolicy;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class EvaluationClockConfiguration {
    @Bean("evaluationClock")
    public Clock evaluationClock() { return Clock.system(EvaluationAccessPolicy.ACADEMIC_ZONE); }
}
