package com.betterschool.marketing;

import com.betterschool.marketing.config.FqcnAnnotationBeanNameGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 营销服务启动类（points 域；票 07 起加 lottery 域，两域共用一个 app）：必须放在根包，
 * 才能扫描到 trigger / case / domain / infrastructure / api / types 各模块。
 * <p>
 * nameGenerator 用 FQCN：两域同名类多，默认简单类名派生 bean 名会运行期冲突（见生成器注释）。
 */
@SpringBootApplication(nameGenerator = FqcnAnnotationBeanNameGenerator.class)
public class MarketingApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingApplication.class, args);
    }
}
