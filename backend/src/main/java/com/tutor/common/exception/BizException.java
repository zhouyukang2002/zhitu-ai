package com.tutor.common.exception;

import lombok.Getter;

/**
 * 业务异常：message 直接透出给前端错误条。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(400, message);
    }

    public static BizException notFound(String message) {
        return new BizException(404, message);
    }

    public static BizException badRequest(String message) {
        return new BizException(400, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(403, message);
    }
}
