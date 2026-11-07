package cn.inventory.types.exception;

import cn.inventory.types.enums.ResponseCode;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 应用异常：带业务码的可预期异常。
 *
 * <p>只有「调用方能据此做不同动作」的失败才值得定义异常；库存不足这类正常业务分支
 * 走 {@code DeductResultVO.status}，不抛异常（抛异常会吃掉吞吐，也污染日志）。
 */
@Getter
@EqualsAndHashCode(callSuper = true)
public class AppException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String info;

    public AppException(String code, String info) {
        super(info);
        this.code = code;
        this.info = info;
    }

    public AppException(ResponseCode responseCode) {
        this(responseCode.getCode(), responseCode.getInfo());
    }

    public AppException(ResponseCode responseCode, String info) {
        super(info);
        this.code = responseCode.getCode();
        this.info = info;
    }
}
