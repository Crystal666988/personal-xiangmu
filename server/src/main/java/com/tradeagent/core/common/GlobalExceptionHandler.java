package com.tradeagent.core.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局统一异常拦截处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("【上传大小超限】文件超过最大限制: {}", e.getMessage());
        return ApiResponse.error(413, "文件上传失败：文件体积超过系统限制（最大允许单文件 200MB）");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("【业务参数非法】{}", e.getMessage());
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleGeneralException(Exception e) {
        log.error("【系统未捕获异常】{}", e.getMessage(), e);
        return ApiResponse.error(500, "系统内部异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
    }
}
