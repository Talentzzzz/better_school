package com.betterschool;

import com.betterschool.config.FqcnAnnotationBeanNameGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 单体启动类（营销四域 inventory/points/raffle/rebate 随 monolith-restructure 票 05 并入）：
 * 必须放在根包，才能扫描到 trigger / case / domain / infrastructure / api / types 各模块。
 * <p>
 * nameGenerator 用 FQCN（营销侧 MarketingApplication 既有先例）：营销域并入后存在跨包同名类
 * （如 infrastructure.merge 与 infrastructure.merge.count 各一份 MergeConfig/MergeResult、
 * points/listener 与 listener 各一份 LotteryDrawnConsumer），默认简单类名派生 bean 名会运行期冲突；
 * 组件扫描与 @MapperScan 都挂本生成器，bean 名改用 FQCN，注入一律按类型，改名不影响装配。
 */
@SpringBootApplication(nameGenerator = FqcnAnnotationBeanNameGenerator.class)
public class BetterSchoolApplication {

    public static void main(String[] args) {
        SpringApplication.run(BetterSchoolApplication.class, args);
    }
}
