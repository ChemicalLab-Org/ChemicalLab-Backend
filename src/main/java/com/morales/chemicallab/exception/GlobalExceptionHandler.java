package com.morales.chemicallab.exception;

import com.morales.chemicallab.security.limits.InputRejectedException;

import com.morales.chemicallab.dto.ErrorResponse;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InputRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleInput(InputRejectedException ex) {
        var response = ResponseEntity.status(ex.status());
        if (ex.status() == 429 || ex.status() == 503) response.header("Retry-After", "1");
        return response.body(Map.of("status", ex.status(), "code", ex.code(), "message", ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable() {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "code", "INPUT_INVALID", "message", "La entrada JSON no es válida."));
    }

    @ExceptionHandler(CurrentPasswordInvalidException.class)
    public ResponseEntity<Map<String, Object>> handleCurrentPasswordInvalid(CurrentPasswordInvalidException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", HttpStatus.BAD_REQUEST.value(),
                "code", "CURRENT_PASSWORD_INVALID",
                "message", ex.getMessage()));
    }

    @ExceptionHandler(LoginRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleLoginRejected(LoginRejectedException ex) {
        return ResponseEntity.status(401).body(Map.of("status", 401, "code", "LOGIN_INVALID", "message", ex.getMessage()));
    }

    @ExceptionHandler(AuthenticationLimitException.class)
    public ResponseEntity<Map<String, Object>> handleLimit(AuthenticationLimitException ex) {
        return ResponseEntity.status(ex.status()).header("Retry-After", Long.toString(ex.retryAfter()))
                .body(Map.of("status", ex.status(), "code", ex.status() == 429 ? "AUTH_RATE_LIMITED" : "AUTH_LIMITS_UNAVAILABLE",
                        "message", ex.getMessage(), "retryAfterSeconds", ex.retryAfter()));
    }

    @ExceptionHandler(com.morales.chemicallab.security.audit.AuditUnavailableException.class)
    public ResponseEntity<Map<String,Object>> handleAuditUnavailable(){
        return ResponseEntity.status(503).body(Map.of("status",503,"code","AUDIT_UNAVAILABLE","message","No se pudo confirmar la operación. Inténtalo de nuevo."));
    }

    // Manejadores existentes — mantienen compatibilidad con el motor químico

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", false);
        response.put("error", "Solicitud inválida");
        response.put("message", ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", false);
        response.put("error", "Archivo demasiado grande");
        response.put("message", "El archivo supera el tamaño máximo permitido (10 MB).");

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", false);
        response.put("error", "Error de validación");

        Map<String, String> details = new LinkedHashMap<>();

        ex.getBindingResult().getFieldErrors().forEach(fieldError ->
                details.put(fieldError.getField(), fieldError.getDefaultMessage())
        );

        response.put("details", details);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // Manejadores para el módulo de usuarios

    @ExceptionHandler({EntityNotFoundException.class, NoSuchElementException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(RuntimeException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.NOT_FOUND.value(),
                "Recurso no encontrado",
                ex.getMessage(),
                request.getRequestURI(),
                null
        ));
    }

    // Manejadores de autenticación — devuelven 401 con mensaje claro

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.UNAUTHORIZED.value(),
                "Credenciales inválidas",
                ex.getMessage(),
                request.getRequestURI(),
                null
        ));
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ErrorResponse> handleDisabled(DisabledException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.UNAUTHORIZED.value(),
                "Cuenta inactiva",
                ex.getMessage(),
                request.getRequestURI(),
                null
        ));
    }

    /**
     * Ruta inexistente. Desde Spring Boot 3.2 (este proyecto usa Boot 4), una petición que no
     * coincide con ningún controlador ni recurso estático lanza {@link NoResourceFoundException} /
     * {@link NoHandlerFoundException}, cuyo significado es <strong>404</strong>. Sin este manejador
     * caerían en el {@code handleGeneral(Exception)} de abajo y se devolverían como 500 engañoso
     * (p. ej. el frontend llamando a un endpoint que aún no está desplegado). Aquí se mapean a 404
     * claro.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoHandler(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.NOT_FOUND.value(),
                "Recurso no encontrado",
                "La ruta solicitada no existe.",
                request.getRequestURI(),
                null
        ));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatus(org.springframework.web.server.ResponseStatusException ex,
                                                     HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatusCode()).body(new ErrorResponse(
                LocalDateTime.now(), ex.getStatusCode().value(), "Solicitud no disponible",
                ex.getReason(), request.getRequestURI(), null));
    }

    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionSystemException.class,org.springframework.transaction.TransactionTimedOutException.class})
    public ResponseEntity<Map<String,Object>> handleStorage(RuntimeException ex) {
        if(ex instanceof org.springframework.dao.QueryTimeoutException || ex instanceof org.springframework.transaction.TransactionTimedOutException)
            return ResponseEntity.status(503).body(Map.of("status",503,"code","QUERY_TIMEOUT","message","La consulta excedió el tiempo disponible. Reduce el intervalo o los filtros e inténtalo de nuevo."));
        if(com.morales.chemicallab.security.storage.StorageErrors.quota(ex))
            return ResponseEntity.status(409).body(Map.of("status",409,"code","STORAGE_QUOTA_EXCEEDED","message",com.morales.chemicallab.security.storage.StorageErrors.message()));
        return ResponseEntity.status(500).body(Map.of("status",500,"message","No se pudo guardar la operación."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneral(Exception ex, HttpServletRequest request) {
        if(com.morales.chemicallab.security.storage.StorageErrors.quota(ex))
            return ResponseEntity.status(409).body(Map.of("status",409,"code","STORAGE_QUOTA_EXCEEDED","message",com.morales.chemicallab.security.storage.StorageErrors.message()));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Error interno del servidor",
                "Ocurrió un error inesperado. Por favor, intente de nuevo.",
                request.getRequestURI(),
                null
        ));
    }
}
