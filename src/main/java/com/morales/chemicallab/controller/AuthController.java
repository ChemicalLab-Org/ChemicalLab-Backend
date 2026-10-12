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
    private final com.morales.chemicallab.security.audit.AuditRejections audit;
    private final com.morales.chemicallab.security.AuthenticationLimits limits;

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        var budget = limits.login(request);
        try { return authService.login(request, budget); }
        catch (com.morales.chemicallab.exception.LoginRejectedException ex) {
            if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                    instanceof org.springframework.web.context.request.ServletRequestAttributes attributes)
                attributes.getRequest().setAttribute(com.morales.chemicallab.security.audit.AuditRequestFilter.LOGIN_REJECTED,true);
            audit.record(com.morales.chemicallab.entity.LogEventType.LOGIN_FAILED,null,"UserAccount",null,"reason="+ex.auditReason(),request.usernameOrEmail());
            throw ex;
        }
    }

    @GetMapping("/me")
    public CurrentUserResponse currentUser() {
        return authService.getCurrentUser();
    }

    @PatchMapping("/change-temporary-password")
    public ResponseEntity<PasswordChangeResponse> changeTemporaryPassword(
            @Valid @RequestBody ChangePasswordRequest request) {
        limits.change();
        return ResponseEntity.ok(authService.changeTemporaryPassword(request));
    }
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        authService.logout(false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll() {
        authService.logout(true);
        return ResponseEntity.noContent().build();
    }
}
