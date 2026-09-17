package com.knobase.business;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiModels.ErrorResponse> api(ApiException e) {
        return response(e.status().value(), e.getMessage());
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiModels.ErrorResponse> validation(BindException e) {
        var error = e.getBindingResult().getFieldErrors().stream().findFirst();
        return response(400, error.map(f -> f.getField() + ": " + f.getDefaultMessage()).orElse("请求参数无效"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiModels.ErrorResponse> constraint(ConstraintViolationException e) {
        return response(400, "请求参数无效：" + e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiModels.ErrorResponse> malformed(HttpMessageNotReadableException e) {
        return response(400, "请求 JSON 无效、包含不支持的字段或字段类型错误");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiModels.ErrorResponse> tooLarge(MaxUploadSizeExceededException e) {
        return response(413, "单个文件不得超过 10MB，一次上传总量不得超过 100MB");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiModels.ErrorResponse> multipart(MultipartException e) {
        return response(400, "无法读取上传文件，请使用 multipart/form-data 和 files 字段");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiModels.ErrorResponse> conflict(DataIntegrityViolationException e) {
        return response(409, "数据已发生变化，请刷新后重试");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiModels.ErrorResponse> unexpected(Exception e) {
        if (e instanceof ErrorResponse error) {
            int status = error.getStatusCode().value();
            return response(status, switch (status) {
                case 404 -> "接口或资源不存在";
                case 405 -> "不支持此请求方法";
                case 415 -> "不支持此请求格式";
                default -> status < 500 ? "请求参数缺失或格式不正确" : "服务暂时不可用";
            });
        }
        log.error("Unhandled business API error", e);
        return response(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务处理失败，请稍后重试");
    }

    private ResponseEntity<ApiModels.ErrorResponse> response(int status, String message) {
        return ResponseEntity.status(status).body(new ApiModels.ErrorResponse(message));
    }
}
