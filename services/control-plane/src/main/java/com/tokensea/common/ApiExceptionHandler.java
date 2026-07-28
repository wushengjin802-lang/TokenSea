package com.tokensea.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException exception) {
        String message = exception.getReason() == null || exception.getReason().isBlank()
                ? "请求未满足业务条件"
                : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(ApiResponse.fail(message));
    }

    @ExceptionHandler(OperationException.class)
    public ResponseEntity<Map<String, Object>> handleOperation(OperationException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", exception.problem());
        body.put("code", exception.code());
        body.put("location", exception.location());
        body.put("problem", exception.problem());
        body.put("action", exception.action());
        return ResponseEntity.status(exception.status()).body(body);
    }
}
