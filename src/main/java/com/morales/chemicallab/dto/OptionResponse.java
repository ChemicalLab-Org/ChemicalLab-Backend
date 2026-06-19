package com.morales.chemicallab.dto;

/**
 * Alternativa vista por el docente o el administrador. Incluye el campo
 * {@code correct}, que nunca se expone al estudiante antes de enviar su intento.
 */
public record OptionResponse(
        Long id,
        String optionText,
        Boolean correct,
        Integer orderIndex
) {}
