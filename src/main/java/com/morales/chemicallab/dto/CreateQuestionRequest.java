package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * Alta de una pregunta. El tipo determina qué campos aplican: para
 * {@code MULTIPLE_CHOICE} se exigen alternativas con exactamente una correcta; para
 * {@code OPEN_TEXT} no se piden alternativas y puede incluirse {@code expectedAnswer}
 * (criterio de corrección visible solo para el docente). Esas reglas específicas se
 * validan en el servicio según el tipo.
 */
public record CreateQuestionRequest(

        @NotBlank(message = "El enunciado de la pregunta es obligatorio")
        String questionText,

        // Opcional: si no se envía, el servicio asume MULTIPLE_CHOICE.
        QuestionType questionType,

        @NotNull(message = "El puntaje es obligatorio")
        @Min(value = 1, message = "El puntaje debe ser al menos 1")
        Integer points,

        Integer orderIndex,

        String explanation,

        // Solo preguntas abiertas: respuesta esperada o criterio de corrección (solo docente).
        @Size(max = 3000, message = "El criterio de corrección no puede superar 3000 caracteres")
        String expectedAnswer,

        // Si la pregunta es obligatoria. Por defecto true cuando no se envía.
        Boolean required,

        // Alternativas. Obligatorias solo para preguntas de alternativa única; se ignoran
        // en preguntas abiertas. La cantidad mínima se valida en el servicio según el tipo.
        @Valid
        List<CreateOptionRequest> options

) {}
