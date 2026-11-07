package cn.inventory.trigger.http;

import cn.inventory.api.response.Response;
import cn.inventory.types.enums.ResponseCode;
import cn.inventory.types.exception.AppException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.stream.Collectors;

/**
 * 全局异常兜底：把异常翻译成统一响应体，并按「可判读的桶」计数
 *
 * <p>两个原则：
 * <ol>
 *   <li><b>异常永远不裸奔到 HTTP 层</b>：5xx 会被负载均衡/网关当成「服务挂了」，
 *       而实际的失败原因（锁等待、池排队）对调用方毫无意义，只对运维有意义；</li>
 *   <li><b>异常必须分桶</b>：把 1205（锁等待超时）、1213（死锁）、池排队、驱动超时
 *       混成同一个 ERROR，压测后就无从判断「到底卡在哪」。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final DeductMetrics deductMetrics;

    public GlobalExceptionHandler(DeductMetrics deductMetrics) {
        this.deductMetrics = deductMetrics;
    }

    /** 参数校验失败（@Valid 触发）——属于调用方问题，不计入扣减失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Response<Void> handleInvalidArgument(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return Response.fail(ResponseCode.PARAM_ERROR.getCode(), message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Response<Void> handleConstraintViolation(ConstraintViolationException e) {
        return Response.fail(ResponseCode.PARAM_ERROR.getCode(), e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Response<Void> handleUnreadableBody(HttpMessageNotReadableException e) {
        return Response.fail(ResponseCode.PARAM_ERROR.getCode(), "请求体格式错误");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Response<Void> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return Response.fail(ResponseCode.PARAM_ERROR.getCode(), "Content-Type 必须是 application/json");
    }

    @ExceptionHandler(AppException.class)
    public Response<Void> handleAppException(AppException e) {
        log.warn("业务异常 code={} info={}", e.getCode(), e.getInfo());
        return Response.fail(e.getCode(), e.getInfo());
    }

    @ExceptionHandler(Exception.class)
    public Response<Void> handleUnexpected(Exception e) {
        String bucket = classify(e);
        deductMetrics.recordError(bucket);
        log.error("未预期异常，已归入 {}", bucket, e);
        return Response.fail(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo());
    }

    /**
     * 异常分桶：沿 cause 链最多下钻 10 层，先认 SQL 错误码，再认连接池/驱动异常。
     *
     * @return 桶名，如 {@code ERROR_LOCK_WAIT_TIMEOUT} / {@code ERROR_DEADLOCK} /
     * {@code ERROR_POOL_TIMEOUT} / {@code ERROR_SOCKET_TIMEOUT} / {@code ERROR_SQL_1062}
     */
    static String classify(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof SQLException sqlException) {
                int errorCode = sqlException.getErrorCode();
                return switch (errorCode) {
                    case 1205 -> "ERROR_LOCK_WAIT_TIMEOUT";
                    case 1213 -> "ERROR_DEADLOCK";
                    case 1040 -> "ERROR_TOO_MANY_CONNECTIONS";
                    default -> "ERROR_SQL_" + errorCode;
                };
            }
            if (current instanceof SocketTimeoutException) {
                return "ERROR_SOCKET_TIMEOUT";
            }
            // 按类名判断，避免让 trigger 层依赖 HikariCP
            if ("SQLTransientConnectionException".equals(current.getClass().getSimpleName())) {
                return "ERROR_POOL_TIMEOUT";
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return "ERROR";
    }
}
