package org.dromara.openapi.security;

import lombok.Getter;

@Getter
public class OpenApiAuthenticationException extends RuntimeException {
    private final int status;
    private final String error;

    public OpenApiAuthenticationException(int status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }
}
