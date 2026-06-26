package com.morales.chemicallab.dto;

/**
 * Resultado de la descarga/visualización de un material de tipo archivo. Reúne los bytes
 * y los metadatos necesarios para que el controlador construya la respuesta HTTP con el
 * {@code Content-Type} y el {@code Content-Disposition} correctos.
 *
 * @param inline {@code true} para mostrar el archivo en línea (PDF e imágenes);
 *               {@code false} para forzar la descarga (diapositivas PPT/PPTX).
 */
public record MaterialDownload(
        byte[] data,
        String contentType,
        String fileName,
        boolean inline
) {}
