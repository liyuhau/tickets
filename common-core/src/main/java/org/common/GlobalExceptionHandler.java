package org.common;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.rpc.RpcException;
import org.common.dto.R;
import org.common.dto.ResultCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 * <p>将不同类型的异常映射为统一的 {@link R} 返回格式。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理自定义的业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public R<Object> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return R.failure(e.getCode(), e.getMessage());
    }

    /**
     * 处理 @Valid 注解触发的参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Object> handleValidationExceptions(MethodArgumentNotValidException e) {
        String firstErrorMessage = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", firstErrorMessage);
        return R.failure(ResultCode.PARAM_VALIDATION_ERROR.getCode(), firstErrorMessage);
    }

    /**
     * 处理 Dubbo RPC 调用异常
     */
    @ExceptionHandler(RpcException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Object> handleRpcException(RpcException e) {
        log.error("RPC调用失败: {}", e.getMessage(), e);
        if (e.isTimeout()) {
            return R.failure(ResultCode.RPC_TIMEOUT);
        }
        return R.failure(ResultCode.SERVICE_UNAVAILABLE);
    }

    /**
     * 兜底处理所有未被捕获的异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Object> handleAllUncaughtException(Exception e) {
        log.error("发生未知异常", e);
        return R.failure(ResultCode.INTERNAL_SERVER_ERROR);
    }
}
