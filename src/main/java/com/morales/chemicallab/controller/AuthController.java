package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.AuthResponse;
import com.morales.chemicallab.dto.ChangePasswordRequest;
import com.morales.chemicallab.dto.CurrentUserResponse;
import com.morales.chemicallab.dto.LoginRequest;
import com.morales.chemicallab.dto.PasswordChangeResponse;
import com.morales.chemicallab.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public CurrentUserResponse currentUser() {
        return authService.getCurrentUser();
    }

    @PatchMapping("/change-temporary-password")
    public ResponseEntity<PasswordChangeResponse> changeTemporaryPassword(
            @Valid @RequestBody ChangePasswordRequest request) {
        return ResponseEntity.ok(authService.changeTemporaryPassword(request));
    }
}
