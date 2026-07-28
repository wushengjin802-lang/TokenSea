package com.tokensea.common;

import org.springframework.http.HttpStatus;

public class OperationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String location;
    private final String problem;
    private final String action;

    public OperationException(HttpStatus status, String code, String location, String problem, String action) {
        super(problem);
        this.status = status;
        this.code = code;
        this.location = location;
        this.problem = problem;
        this.action = action;
    }

    public static OperationException conflict(String code, String location, String problem, String action) {
        return new OperationException(HttpStatus.CONFLICT, code, location, problem, action);
    }

    public static OperationException badRequest(String code, String location, String problem, String action) {
        return new OperationException(HttpStatus.BAD_REQUEST, code, location, problem, action);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String location() { return location; }
    public String problem() { return problem; }
    public String action() { return action; }
}
