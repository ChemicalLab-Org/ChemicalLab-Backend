package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.QuestionDisplayMode;
import jakarta.validation.constraints.*;

public record UpdateEvaluationRequest(

        @NotBlank(message = "El título es obligatorio")
        @Size(max = 150, message = "El título no puede superar 150 caracteres")
        String title,

        @Size(max = 1000, message = "La descripción no puede superar 1000 caracteres")
        String description,

        @Size(max = 2000, message = "Las instrucciones no pueden superar 2000 caracteres")
        String instructions,

        @Size(max = 120, message = "El tema no puede superar 120 caracteres")
        String topic,

        @NotNull(message = "El número máximo de intentos es obligatorio")
        @Min(value = 1, message = "El número máximo de intentos debe ser al menos 1")
        @Max(value = 10, message = "El número máximo de intentos no puede superar 10")
        Integer maxAttempts,

        @Positive(message = "El límite de tiempo debe ser positivo")
        @Max(value = 240, message = "El límite de tiempo no puede superar 240 minutos")
        Integer timeLimitMinutes,

        // Configuración avanzada. Si llegan null, el servicio aplica los valores por
        // defecto seguros (calculadora y detección desactivadas, preguntas todas juntas).
        Boolean allowChemicalCalculator,

        Boolean trackTabExit,

        QuestionDisplayMode questionDisplayMode,

        Boolean randomizeQuestions,

        Boolean allowPeriodicTable

) {}
