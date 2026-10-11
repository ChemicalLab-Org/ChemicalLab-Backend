package com.morales.chemicallab.validation;

import com.morales.chemicallab.security.PasswordPolicy;
import jakarta.validation.*;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NewPassword.Validator.class)
public @interface NewPassword {
    String message() default PasswordPolicy.MESSAGE;
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NewPassword, String> {
        @Override public boolean isValid(String value, ConstraintValidatorContext context) {
            return PasswordPolicy.valid(value);
        }
    }
}
