package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record CreateQuestionRequest(

        @NotBlank(message = "El enunciado de la pregunta es obligatorio")
        String questionText,

        // Opcional: si no se envía, el servicio asume MULTIPLE_CHOICE (único tipo del MVP).
        QuestionType questionType,

        @NotNull(message = "El puntaje es obligatorio")
        @Min(value = 1, message = "El puntaje debe ser al menos 1")
        Integer points,

        Integer orderIndex,

        String explanation,

        @Valid
        @NotNull(message = "La pregunta debe incluir alternativas")
        @Size(min = 2, message = "La pregunta debe tener al menos dos alternativas")
        List<CreateOptionRequest> options

) {}
