package com.betterschool.domain.timeout.model.valobj;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量重发死信的账：成功/失败双清单（内嵌化票 06，照原独立中心的形状）。
 * <p>
 * 失败项不是异常而是**正常结果**：管理员勾了一批死信，其中某几条在这几秒里被别人重发过、
 * 或键写错了。逐项回报比「整包失败」有用——成功的那几条确实已经重开了
 * （返回后由下一个扫描周期重投，重发不新开重试通路）。
 */
@Getter
public class DeadLetterResendOutcomeVO {

    private final List<TaskKeyVO> successList = new ArrayList<>();
    private final List<TaskKeyVO> failList = new ArrayList<>();

    /** 记一项成功（确有 FAILED 行被重开） */
    public void success(TaskKeyVO key) {
        successList.add(key);
    }

    /** 记一项失败（该键当前不是 FAILED，无副作用） */
    public void fail(TaskKeyVO key) {
        failList.add(key);
    }
}
