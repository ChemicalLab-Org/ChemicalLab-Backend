package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.service.WhiteboardSessionService;
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
import java.util.List;

/**
 * Endpoints del docente para la pizarra interactiva en vivo. El acceso por rol se controla
 * en SecurityConfig ({@code /api/whiteboards/teacher/**} → DOCENTE); el docente se identifica
 * a partir del usuario autenticado, nunca de identificadores enviados por el cliente.
 */
@RestController
@RequestMapping("/api/whiteboards/teacher")
@RequiredArgsConstructor
public class WhiteboardTeacherController {

    private final WhiteboardSessionService whiteboardSessionService;

    @GetMapping
    public List<WhiteboardSessionResponse> listarMisSesiones(Authentication authentication) {
        return whiteboardSessionService.listTeacherSessions(authentication.getName());
    }

    @PostMapping
    public ResponseEntity<WhiteboardSessionResponse> crearSesion(
            Authentication authentication,
            @Valid @RequestBody WhiteboardSessionCreateRequest request) {
        WhiteboardSessionResponse response =
                whiteboardSessionService.createSession(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public WhiteboardSessionDetailResponse verSesion(
            Authentication authentication,
            @PathVariable Long id) {
        return whiteboardSessionService.getTeacherSessionDetail(authentication.getName(), id);
    }

    @PostMapping("/{id}/pause")
    public WhiteboardSessionResponse pausarSesion(
            Authentication authentication,
            @PathVariable Long id) {
        return whiteboardSessionService.pauseSession(authentication.getName(), id);
    }

    @PostMapping("/{id}/resume")
    public WhiteboardSessionResponse reanudarSesion(
            Authentication authentication,
            @PathVariable Long id) {
        return whiteboardSessionService.resumeSession(authentication.getName(), id);
    }

    @PostMapping(value = "/{id}/close", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public WhiteboardSessionResponse cerrarSesion(
            Authentication authentication,
            @PathVariable Long id,
            @RequestPart("snapshot") MultipartFile snapshot) {
        return whiteboardSessionService.closeSession(authentication.getName(), id, snapshot);
    }

    @PatchMapping("/{id}/interaction")
    public WhiteboardSessionResponse actualizarInteraccionGlobal(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody WhiteboardGlobalInteractionRequest request) {
        return whiteboardSessionService.updateGlobalInteraction(authentication.getName(), id, request);
    }

    @PatchMapping("/{id}/participants/{studentId}/interaction")
    public WhiteboardParticipantResponse actualizarInteraccionIndividual(
            Authentication authentication,
            @PathVariable Long id,
            @PathVariable Long studentId,
            @Valid @RequestBody WhiteboardParticipantInteractionRequest request) {
        return whiteboardSessionService.updateParticipantInteraction(
                authentication.getName(), id, studentId, request);
    }

    @GetMapping("/{id}/participants")
    public List<WhiteboardParticipantResponse> listarParticipantes(
            Authentication authentication,
            @PathVariable Long id) {
        return whiteboardSessionService.listParticipants(authentication.getName(), id);
    }

    @GetMapping("/{id}/snapshot")
    public ResponseEntity<byte[]> obtenerCaptura(
            Authentication authentication,
            @PathVariable Long id) {
        WhiteboardSnapshotDownload download =
                whiteboardSessionService.getTeacherSnapshot(authentication.getName(), id);
        return snapshotResponse(download);
    }

    /** Construye la respuesta HTTP de descarga de la captura final como imagen en línea. */
    static ResponseEntity<byte[]> snapshotResponse(WhiteboardSnapshotDownload download) {
        ContentDisposition disposition = ContentDisposition.builder("inline")
                .filename(download.fileName() != null ? download.fileName() : "captura",
                        StandardCharsets.UTF_8)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(disposition);
        headers.setContentType(resolveMediaType(download.contentType()));
        headers.setContentLength(download.data().length);

        return ResponseEntity.ok().headers(headers).body(download.data());
    }

    private static MediaType resolveMediaType(String contentType) {
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
