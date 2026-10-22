package com.lostsystem.exception;

import lombok.Getter;

/**
 * 业务异常。
 *
 * code 使用标准 HTTP 状态码：
 *
 * 400 参数错误
 * 401 未登录或登录失效
 * 403 没有权限
 * 404 资源不存在
 * 500 服务端错误
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(Integer code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}