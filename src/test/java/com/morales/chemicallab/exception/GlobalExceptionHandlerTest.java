package com.morales.chemicallab.exception;

import com.morales.chemicallab.dto.ErrorResponse;
import com.morales.chemicallab.dto.ChangePasswordRequest;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Pruebas del manejo global de excepciones, en particular que una ruta inexistente devuelve
 * <strong>404</strong> y no un 500 engañoso (regresión: en Spring Boot 4 una petición sin handler
 * lanza {@link NoResourceFoundException}, que el manejador genérico {@code Exception} convertía en
 * 500 — p. ej. el frontend llamando a {@code /api/whiteboards/student/{id}/state} contra un backend
 * donde ese endpoint aún no está desplegado).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @ParameterizedTest
    @CsvSource({"current-password,400", "credentials,401", "disabled,401", "argument,400",
            "missing,404", "upload,413", "unexpected,500"})
    void mvcResolvesSpecificFormErrorWithoutChangingOtherContracts(String kind, int status) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ErrorContractController()).setControllerAdvice(handler).build();
        var result = mvc.perform(get("/test-errors/" + kind)).andExpect(status().is(status));
        if (kind.equals("current-password")) {
            result.andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"))
                    .andExpect(jsonPath("$.message").value("La contraseña actual es incorrecta."));
        } else {
            result.andExpect(jsonPath("$.code").doesNotExist());
        }
        if (kind.equals("argument")) {
            result.andExpect(jsonPath("$.valid").value(false))
                    .andExpect(jsonPath("$.message").value("Invalid form"));
        }
    }

    @Test
    void beanValidationStillReturns400WithFieldDetails() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ErrorContractController()).setControllerAdvice(handler).build();
        mvc.perform(post("/test-errors/validate").contentType("application/json")
                        .content("{\"currentPassword\":\"\",\"newPassword\":\"\",\"confirmPassword\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.details.currentPassword").exists())
                .andExpect(jsonPath("$.details.newPassword").exists())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @RestController
    static class ErrorContractController {
        @GetMapping("/test-errors/{kind}")
        public void fail(@PathVariable String kind) {
            throw switch (kind) {
                case "current-password" -> new CurrentPasswordInvalidException();
                case "credentials" -> new BadCredentialsException("Invalid session");
                case "disabled" -> new DisabledException("Inactive account");
                case "argument" -> new IllegalArgumentException("Invalid form");
                case "missing" -> new EntityNotFoundException("Missing resource");
                case "upload" -> new MaxUploadSizeExceededException(10);
                default -> new RuntimeException("Unexpected");
            };
        }

        @PostMapping("/test-errors/validate")
        public void validate(@Valid @RequestBody ChangePasswordRequest request) {}
    }

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
