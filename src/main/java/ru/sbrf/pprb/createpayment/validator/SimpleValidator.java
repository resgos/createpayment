package ru.sbrf.pprb.createpayment.validator;

import org.springframework.stereotype.Component;

@Component
public class SimpleValidator {

    public void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Field '" + field + "' must not be blank");
        }
    }

    public void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Field '" + field + "' must not be null");
        }
    }
}
