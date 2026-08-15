package com.example.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "参数错误"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "库存不足"),
    INSUFFICIENT_CREDITS(HttpStatus.CONFLICT, "信用点不足"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后再试");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
