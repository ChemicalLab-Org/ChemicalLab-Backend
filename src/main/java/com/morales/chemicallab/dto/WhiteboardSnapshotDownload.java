package com.morales.chemicallab.dto;

/**
 * Portador interno de la captura final de una sesión para los endpoints de descarga. No es
 * un cuerpo JSON: los bytes se escriben directamente en la respuesta HTTP con su tipo.
 */
public record WhiteboardSnapshotDownload(
        byte[] data,
        String contentType,
        String fileName
) {
}
