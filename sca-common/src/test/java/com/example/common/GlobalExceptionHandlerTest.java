package com.example.common;

import com.example.exception.InsufficientStockException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionMapsToItsStatus() {
        ProblemDetail problem = handler.handleBusiness(new InsufficientStockException("库存不足"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).isEqualTo("库存不足");
        assertThat(problem.getProperties()).containsEntry("code", "INSUFFICIENT_STOCK");
    }

    @Test
    void illegalArgumentMapsTo400() {
        ProblemDetail problem = handler.handleIllegalArgument(new IllegalArgumentException("count 必须大于 0"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void responseStatusPreservesStatus() {
        ProblemDetail problem = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid internal token"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(problem.getDetail()).isEqualTo("invalid internal token");
    }

    @Test
    void unknownExceptionMapsTo500() {
        ProblemDetail problem = handler.handleOther(new RuntimeException("boom"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }
}
