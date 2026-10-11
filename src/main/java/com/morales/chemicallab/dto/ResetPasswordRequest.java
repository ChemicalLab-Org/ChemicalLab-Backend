package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;

public record ResetPasswordRequest(

        @com.morales.chemicallab.validation.NewPassword
        String newTemporaryPassword,

        @com.morales.chemicallab.validation.NewPassword
        String confirmTemporaryPassword

) {}
