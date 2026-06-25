package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.MaterialType;

import java.util.Set;

/**
 * Metadata segura de un material de apoyo para el docente, el estudiante o el
 * administrador. <strong>Nunca</strong> incluye los bytes del archivo ni rutas internas
 * del servidor: solo la información necesaria para listarlo, previsualizarlo o descargarlo.
 *
 * <ul>
 *   <li>{@code previewAvailable}: indica si el material puede previsualizarse dentro del
 *       sistema (PDF e imágenes). Las diapositivas (PPT/PPTX) se ofrecen solo para descarga.</li>
 *   <li>{@code downloadUrl}: ruta relativa del endpoint de descarga/visualización para
 *       archivos. Es nula para enlaces externos, que se abren mediante {@code url}.</li>
 * </ul>
 */
public record ConceptMaterialResponse(
        Long materialId,
        MaterialType type,
        String title,
        String originalFileName,
        String contentType,
        Long fileSize,
        String url,
        boolean previewAvailable,
        String downloadUrl
) {

    // Tipos que un navegador puede mostrar en línea; el resto se ofrece como descarga.
    private static final Set<String> PREVIEWABLE_CONTENT_TYPES =
            Set.of("application/pdf", "image/png", "image/jpeg");

    /**
     * Construye la respuesta a partir de la proyección de metadata y del identificador del
     * contenido al que pertenece el material (necesario para componer la URL de descarga).
     */
    public static ConceptMaterialResponse fromView(Long conceptId, ConceptMaterialView view) {
        if (view.type() == MaterialType.LINK) {
            return new ConceptMaterialResponse(
                    view.id(), MaterialType.LINK, view.title(),
                    null, null, null, view.url(), false, null);
        }

        boolean previewAvailable = view.contentType() != null
                && PREVIEWABLE_CONTENT_TYPES.contains(view.contentType());
        String downloadUrl = "/api/concepts/" + conceptId + "/materials/" + view.id() + "/download";

        return new ConceptMaterialResponse(
                view.id(), MaterialType.FILE, view.title(),
                view.originalFileName(), view.contentType(), view.fileSize(),
                null, previewAvailable, downloadUrl);
    }
}
