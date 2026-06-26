package com.morales.chemicallab.dto;

/**
 * Nomenclatura de un compuesto en sus tres sistemas escolares.
 *
 * Cada campo es un nombre ya resuelto y listo para mostrarse al estudiante:
 * <ul>
 *   <li>{@code traditional}: nomenclatura tradicional (sufijos -oso/-ico,
 *       prefijos hipo-/per- o forma «de + elemento» para valencia única).</li>
 *   <li>{@code stock}: nomenclatura de Stock (número de oxidación en números
 *       romanos solo cuando el elemento tiene más de una valencia).</li>
 *   <li>{@code systematic}: nomenclatura sistemática (prefijos multiplicadores
 *       mono-, di-, tri-…).</li>
 * </ul>
 *
 * {@code notes} es opcional y se usa para aclarar cuándo se aplicó una forma
 * simplificada o documentada; nunca es {@code null} (cadena vacía si no aplica).
 * Los tres nombres principales tampoco son {@code null} ni vacíos.
 */
public record NomenclatureResponse(
        String traditional,
        String stock,
        String systematic,
        String notes
) {
}
