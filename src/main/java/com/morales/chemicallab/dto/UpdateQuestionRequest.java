package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * Edición de una pregunta. Igual que en el alta, las reglas según el tipo (alternativas
 * obligatorias en {@code MULTIPLE_CHOICE}, {@code expectedAnswer} opcional en
 * {@code OPEN_TEXT}) se validan en el servicio.
 */
public record UpdateQuestionRequest(

        @NotBlank(message = "El enunciado de la pregunta es obligatorio")
        String questionText,

        QuestionType questionType,

        @NotNull(message = "El puntaje es obligatorio")
        @Min(value = 1, message = "El puntaje debe ser al menos 1")
        Integer points,

        Integer orderIndex,

        String explanation,

        @Size(max = 3000, message = "El criterio de corrección no puede superar 3000 caracteres")
        String expectedAnswer,

        Boolean required,

        @Valid
        List<CreateOptionRequest> options

) {}
