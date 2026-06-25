package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.MaterialType;

/**
 * Proyección interna de la metadata de un material de apoyo. Se obtiene mediante una
 * consulta JPQL que selecciona únicamente las columnas necesarias, de modo que los bytes
 * del archivo ({@code fileData}) nunca se carguen en listados. No se expone directamente
 * al cliente: se transforma en {@link ConceptMaterialResponse}.
 */
public record ConceptMaterialView(
        Long id,
        MaterialType type,
        String title,
        String originalFileName,
        String contentType,
        Long fileSize,
        String url
) {}
