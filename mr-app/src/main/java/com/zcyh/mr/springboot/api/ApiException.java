package com.zcyh.mr.springboot.api;

import org.springframework.http.HttpStatus;

/**
 * 对外接口异常。
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private ApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String code, String message, Throwable cause) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, cause);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message, null);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, null);
    }

    public static ApiException serviceUnavailable(String code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message, null);
    }

    public static ApiException serviceUnavailable(String code, String message, Throwable cause) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message, cause);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
