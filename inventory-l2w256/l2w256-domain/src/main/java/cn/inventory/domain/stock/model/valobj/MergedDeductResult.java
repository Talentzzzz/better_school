package cn.inventory.domain.stock.model.valobj;

import lombok.Getter;
import lombok.ToString;

import java.util.Set;

/**
 * 合并落库结果
 *
 * <p>为什么不是 boolean：批量 {@code INSERT IGNORE} 有两种让 {@code affected} 变小的情况，
 * 语义完全不同，不能混：
 * <ul>
 *   <li>整批都是<b>重放</b>（bizNo 全存在）→ 扣减量为 0，这一批「已扣过」，等价于成功；</li>
 *   <li>批里<b>混了</b>几条重放 → 扣减量要剔除已扣份额，其余照扣；
 *       被剔除的那几条要回 {@code DUPLICATE}，而不是 {@code SUCCESS}——
 *       否则对账时「成功笔数」会虚高。</li>
 * </ul>
 */
@Getter
@ToString
public class MergedDeductResult {

    /** 整批扣成功、且没有重放 */
    public static final MergedDeductResult OK = new MergedDeductResult(true, Set.of());

    /** 库存不足：整批未动、事务已回滚 */
    public static final MergedDeductResult INSUFFICIENT = new MergedDeductResult(false, Set.of());

    /** true=库存已扣（含整批重放）；false=库存不足，整批未动 */
    private final boolean deducted;

    /** 本次命中幂等的 bizNo 集合（无重复时为 {@link Set#of()}，零分配） */
    private final Set<String> duplicateBizNos;

    public MergedDeductResult(boolean deducted, Set<String> duplicateBizNos) {
        this.deducted = deducted;
        this.duplicateBizNos = duplicateBizNos;
    }

    public boolean hasDuplicate() {
        return !duplicateBizNos.isEmpty();
    }
}
