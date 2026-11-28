package com.betterschool.infrastructure.timeout;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量重发死信的账：成功/失败双清单。
 * <p>
 * 失败项不是异常而是<b>正常结果</b>：管理员勾了一批死信，其中某几条在这几秒里被别人重发过、
 * 或键写错了。逐项回报比「整包失败」有用——成功的那几条确实已经重开了
 * （返回后由下一个扫描周期重投，重发不新开重试通路）。
 */
@Getter
public class DeadLetterResendOutcome {

    private final List<TaskKey> successList = new ArrayList<>();
    private final List<TaskKey> failList = new ArrayList<>();

    /** 记一项成功（确有 FAILED 行被重开） */
    public void success(TaskKey taskKey) {
        successList.add(taskKey);
    }

    /** 记一项失败（该键当前不是 FAILED，无副作用） */
    public void fail(TaskKey taskKey) {
        failList.add(taskKey);
    }
}
