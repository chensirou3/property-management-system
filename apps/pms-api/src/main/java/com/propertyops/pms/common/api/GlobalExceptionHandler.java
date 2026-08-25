package com.propertyops.pms.common.api;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> handleBusiness(BusinessException error, HttpServletRequest request) {
        return ResponseEntity.status(error.getStatus())
                .body(ApiError.of(error.getCode(), error.getMessage(), requestId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException error, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = error.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation)
                .collect(Collectors.toList());
        ApiError body = new ApiError(
                "VALIDATION_FAILED",
                "请求参数校验失败",
                requestId(request),
                java.time.Instant.now(),
                violations
        );
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraint(ConstraintViolationException error, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = error.getConstraintViolations().stream()
                .map(item -> new ApiError.FieldViolation(item.getPropertyPath().toString(), item.getMessage()))
                .collect(Collectors.toList());
        return ResponseEntity.badRequest().body(new ApiError(
                "VALIDATION_FAILED", "请求参数校验失败", requestId(request), java.time.Instant.now(), violations));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception error, HttpServletRequest request) {
        log.error("Unhandled request failure, requestId={}", requestId(request), error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "系统暂时无法处理请求", requestId(request)));
    }

    private ApiError.FieldViolation toViolation(FieldError error) {
        return new ApiError.FieldViolation(error.getField(),
                Optional.ofNullable(error.getDefaultMessage()).orElse("字段无效"));
    }

    private String requestId(HttpServletRequest request) {
        return Optional.ofNullable((String) request.getAttribute(RequestIdFilter.ATTRIBUTE))
                .orElseGet(() -> Optional.ofNullable(request.getHeader(RequestIdFilter.HEADER)).orElse("unknown"));
    }
}
