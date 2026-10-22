package com.lostsystem.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(
            BusinessException exception
    ) {
        Integer code = exception.getCode();

        HttpStatus status = code == null
                ? null
                : HttpStatus.resolve(code);

        if (status == null || !status.isError()) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        String message = exception.getMessage();

        if (message == null || message.isBlank()) {
            message = "请求处理失败";
        }

        return ResponseEntity
                .status(status)
                .body(Map.of(
                        "code", status.value(),
                        "message", message
                ));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>>
    handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("请求参数错误");

        return ResponseEntity
                .badRequest()
                .body(Map.of(
                        "code", 400,
                        "message", message
                ));
    }
    /**
     * 上传文件超过配置的大小限制。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(
            MaxUploadSizeExceededException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of(
                        "code", 413,
                        "message", "图片不能超过5MB，请压缩后重新上传"
                ));
    }

    /**
     * 上传请求缺少必需的文件参数。
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingUploadPart(
            MissingServletRequestPartException exception
    ) {
        String message = "file".equals(exception.getRequestPartName())
                ? "请选择要上传的图片"
                : "上传请求缺少必需参数";

        return ResponseEntity
                .badRequest()
                .body(Map.of(
                        "code", 400,
                        "message", message
                ));
    }
}