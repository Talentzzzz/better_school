package com.betterschool.marketing.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;

/**
 * 全限定类名 bean 命名器：票 06/07 把 points / lottery 两域并入本工程后存在同名类
 * （Points / Lottery 两域各一份 {@code Constants} 与 {@code PingController}），
 * Spring 默认按简单类名派生 bean 名会
 * ConflictingBeanDefinitionException——包名（域子包）区隔解决不了运行期 bean 名冲突。
 * 组件扫描与 @MapperScan 都挂本生成器，bean 名改用 FQCN；注入一律按类型，改名不影响装配。
 * <p>
 * 形状照旧 {@code marketing-activity} 工程的同名类：票 06（points 域搬入）先把生成器与挂载点备好，
 * 票 07（lottery 域搬入）落地同名类。
 */
public class FqcnAnnotationBeanNameGenerator extends AnnotationBeanNameGenerator {

    @Override
    protected String buildDefaultBeanName(BeanDefinition definition) {
        return definition.getBeanClassName();
    }
}
