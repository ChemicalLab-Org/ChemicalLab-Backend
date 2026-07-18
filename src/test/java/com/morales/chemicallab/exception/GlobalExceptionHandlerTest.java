package com.morales.chemicallab.exception;

import com.morales.chemicallab.dto.ErrorResponse;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas del manejo global de excepciones, en particular que una ruta inexistente devuelve
 * <strong>404</strong> y no un 500 engañoso (regresión: en Spring Boot 4 una petición sin handler
 * lanza {@link NoResourceFoundException}, que el manejador genérico {@code Exception} convertía en
 * 500 — p. ej. el frontend llamando a {@code /api/whiteboards/student/{id}/state} contra un backend
 * donde ese endpoint aún no está desplegado).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }

    @Test
    void rutaInexistenteDevuelve404NoResource() {
        NoResourceFoundException ex = new NoResourceFoundException(
                HttpMethod.GET, "/api/whiteboards/student/4/state", "No static resource.");

        ResponseEntity<ErrorResponse> response =
                handler.handleNoHandler(ex, request("/api/whiteboards/student/4/state"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
    }

    @Test
    void rutaInexistenteDevuelve404NoHandler() {
        NoHandlerFoundException ex =
                new NoHandlerFoundException("GET", "/api/ruta/inexistente", new HttpHeaders());

        ResponseEntity<ErrorResponse> response =
                handler.handleNoHandler(ex, request("/api/ruta/inexistente"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void recursoNoEncontradoSigueDando404() {
        ResponseEntity<ErrorResponse> response = handler.handleNotFound(
                new EntityNotFoundException("La sesión no existe."), request("/api/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void errorRealSigueDando500() {
        ResponseEntity<ErrorResponse> response =
                handler.handleGeneral(new RuntimeException("boom"), request("/api/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
