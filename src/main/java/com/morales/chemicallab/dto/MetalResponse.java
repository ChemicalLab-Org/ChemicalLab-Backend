package com.morales.chemicallab.dto;

import java.util.List;

/**
 * Metal del catálogo del motor químico.
 *
 * Expone los datos que el frontend necesita para armar el selector de metal y
 * las valencias disponibles: símbolo, nombre en español y las valencias
 * positivas permitidas. El backend es la fuente de verdad de estas valencias,
 * por lo que el frontend no necesita mantener su propia lista.
 */
public record MetalResponse(
        String symbol,
        String name,
        List<Integer> valences
) {
}
