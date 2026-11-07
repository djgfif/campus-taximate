package com.campustaximate.server.common;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 모든 오류를 code와 message 형식으로 돌려준다. 예상하지 못한 오류는 내용을 숨기고 로그에 남긴다. */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handle(ApiException e) {
        return body(e.getStatus(), e.getCode(), e.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> handle(OptimisticLockingFailureException e) {
        return body(HttpStatus.CONFLICT, "CONFLICT", "다른 변경과 겹쳤습니다. 화면을 새로고침해 주세요.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handle(DataIntegrityViolationException e) {
        return body(HttpStatus.CONFLICT, "DUPLICATE", "이미 처리된 요청입니다.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handle(Exception e) {
        if (clientGone(e)) {
            log.debug("브라우저가 연결을 끊었습니다.");
            return null;
        }
        log.error("처리하지 못한 오류", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "잠시 문제가 생겼습니다. 다시 시도해 주세요.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_INPUT", "message", "입력값을 확인해 주세요.", "fields", fields));
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e, HttpHeaders headers,
                                                                          HttpStatusCode status, WebRequest request) {
        return body(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "사진은 5MB 이하만 올릴 수 있습니다.");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("서버 오류", e);
        }
        return ResponseEntity.status(status).headers(headers)
                .body(Map.of("code", status.is4xxClientError() ? "BAD_REQUEST" : "INTERNAL_ERROR", "message", "요청을 처리하지 못했습니다."));
    }

    /** 실시간 연결을 브라우저가 닫은 경우다. 응답을 쓸 곳이 없으니 오류로 남기지 않는다. */
    private static boolean clientGone(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.io.IOException || t instanceof org.springframework.web.context.request.async.AsyncRequestNotUsableException) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<Object> body(HttpStatusCode status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
