package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.ConceptMaterialResponse;
import com.morales.chemicallab.dto.CreateMaterialLinkRequest;
import com.morales.chemicallab.dto.MaterialDownload;
import com.morales.chemicallab.service.ConceptMaterialService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * Endpoints de los materiales de apoyo de los contenidos conceptuales.
 *
 * <p>La creación, el reemplazo y el retiro de materiales viven bajo
 * {@code /api/concepts/teacher/**}, restringido a DOCENTE por SecurityConfig; el docente se
 * resuelve del usuario autenticado. La descarga/visualización vive bajo
 * {@code /api/concepts/{conceptId}/materials/**}: requiere autenticación y el control de
 * acceso fino (docente propietario, estudiante asignado o administrador) se aplica en el
 * servicio según el rol.</p>
 */
@RestController
@RequestMapping("/api/concepts")
@RequiredArgsConstructor
public class ConceptMaterialController {

    private final ConceptMaterialService conceptMaterialService;

    // =========================================================================
    // DOCENTE
    // =========================================================================

    @PostMapping(value = "/teacher/{conceptId}/materials/file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ConceptMaterialResponse> subirArchivo(
            Authentication authentication,
            @PathVariable Long conceptId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title) {
        ConceptMaterialResponse response =
                conceptMaterialService.addFileMaterial(authentication.getName(), conceptId, file, title);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/teacher/{conceptId}/materials/link")
    public ResponseEntity<ConceptMaterialResponse> agregarEnlace(
            Authentication authentication,
            @PathVariable Long conceptId,
            @Valid @RequestBody CreateMaterialLinkRequest request) {
        ConceptMaterialResponse response =
                conceptMaterialService.addLinkMaterial(authentication.getName(), conceptId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/teacher/{conceptId}/materials/{materialId}")
    public ResponseEntity<Void> eliminarMaterial(
            Authentication authentication,
            @PathVariable Long conceptId,
            @PathVariable Long materialId) {
        conceptMaterialService.removeMaterial(authentication.getName(), conceptId, materialId);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // DESCARGA / VISUALIZACIÓN (docente, estudiante asignado o administrador)
    // =========================================================================

    @GetMapping("/{conceptId}/materials/{materialId}/download")
    public ResponseEntity<byte[]> descargarMaterial(
            Authentication authentication,
            @PathVariable Long conceptId,
            @PathVariable Long materialId) {
        MaterialDownload download =
                conceptMaterialService.downloadMaterial(authentication.getName(), conceptId, materialId);

        ContentDisposition disposition = ContentDisposition
                .builder(download.inline() ? "inline" : "attachment")
                .filename(download.fileName() != null ? download.fileName() : "material",
                        StandardCharsets.UTF_8)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(disposition);
        headers.setContentType(resolveMediaType(download.contentType()));
        headers.setContentLength(download.data().length);

        return ResponseEntity.ok().headers(headers).body(download.data());
    }

    private MediaType resolveMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
