package com.morales.chemicallab.dto;

/**
 * Alternativa vista por el estudiante. De forma deliberada NO incluye el campo
 * {@code correct}: el estudiante no debe conocer la respuesta correcta antes de
 * enviar su intento.
 */
public record StudentOptionResponse(
        Long id,
        String optionText,
        Integer orderIndex
) {}
