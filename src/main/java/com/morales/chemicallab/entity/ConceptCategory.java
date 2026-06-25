package com.morales.chemicallab.entity;

import java.util.List;

/**
 * Catálogo de categorías sugeridas para los contenidos conceptuales.
 *
 * <p>La categoría de un contenido es texto libre: el docente puede escribir la suya.
 * Esta clase solo aporta una lista de sugerencias por defecto (las categorías químicas
 * clásicas más algunos temas generales habituales) para facilitar el registro desde el
 * frontend. No restringe los valores admitidos ni se persiste como enumeración.</p>
 */
public final class ConceptCategory {

    private ConceptCategory() {
    }

    /**
     * Categorías sugeridas por defecto. Incluye las clásicas de química inorgánica para
     * mantener continuidad con los contenidos ya creados y otros temas frecuentes.
     */
    public static final List<String> DEFAULTS = List.of(
            "Óxidos",
            "Hidróxidos",
            "Ácidos",
            "Sales binarias",
            "Oxisales",
            "Nomenclatura inorgánica",
            "Enlace químico",
            "Reacciones químicas",
            "Tabla periódica",
            "Valencia y número de oxidación",
            "Laboratorio y seguridad",
            "Repaso de evaluación",
            "Clase introductoria",
            "Actividad práctica"
    );
}
