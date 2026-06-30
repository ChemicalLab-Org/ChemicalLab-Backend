package com.morales.chemicallab.dto;

/**
 * Fragmento de texto con un estilo uniforme dentro de un objeto de texto de la pizarra. El
 * formato (negrita/cursiva/subrayado) es por fragmento, lo que permite formato parcial dentro de
 * un mismo bloque; el color y el tamaño son del bloque (van en el evento de dibujo).
 *
 * <p>Es solo transporte en vivo: el backend valida la longitud del texto pero no lo persiste fila
 * por fila ni lo registra en los logs de auditoría.</p>
 */
public record WhiteboardTextRun(
        String text,
        boolean bold,
        boolean italic,
        boolean underline
) {
}
