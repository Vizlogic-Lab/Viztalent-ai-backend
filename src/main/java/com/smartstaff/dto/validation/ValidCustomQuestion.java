package com.smartstaff.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/** Type-specific rules for a hand-written question; see {@link CustomQuestionValidator}. */
@Documented
@Constraint(validatedBy = CustomQuestionValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCustomQuestion {
    String message() default "Invalid question.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
