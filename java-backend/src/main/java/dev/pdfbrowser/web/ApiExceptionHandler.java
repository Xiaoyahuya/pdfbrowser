package dev.pdfbrowser.web;

import dev.pdfbrowser.exception.FileBrowserException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(FileBrowserException.class)
    ResponseEntity<ApiError> handleKnown(FileBrowserException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiError(
                Instant.now(), exception.status().value(), exception.code(), exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " 参数无效")
                .orElse("请求参数无效");
        return ResponseEntity.badRequest().body(new ApiError(
                Instant.now(), 400, "VALIDATION_FAILED", message, request.getRequestURI()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleMalformedJson(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiError(
                Instant.now(), 400, "MALFORMED_JSON", "请求正文不是有效的 JSON", request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected error while handling {}", request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                Instant.now(), 500, "INTERNAL_ERROR", "服务器处理请求时发生错误", request.getRequestURI()));
    }
}
