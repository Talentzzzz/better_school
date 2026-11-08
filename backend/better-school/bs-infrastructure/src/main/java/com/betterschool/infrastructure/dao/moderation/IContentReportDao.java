package com.betterschool.infrastructure.dao.moderation;

import com.betterschool.infrastructure.dao.moderation.po.ContentReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 内容举报表 Mapper（content_report 单表），
 * SQL 定义在 app 模块 resources/mybatis/mapper/ContentReportMapper.xml
 */
@Mapper
public interface IContentReportDao {

    /** INSERT IGNORE：uk_reporter_target 冲突 = 重复举报（幂等），返回影响行数 */
    int insertIgnoreReport(ContentReport report);

    /** 核实：PENDING → PROCESSED，返回影响行数（1=本次刚核实；按业务键 report_id 定位） */
    int processById(@Param("reportId") String reportId, @Param("handlerId") String handlerId);

    /** 忽略：PENDING → IGNORED，返回影响行数 */
    int ignoreById(@Param("reportId") String reportId, @Param("handlerId") String handlerId);

    ContentReport selectReportById(@Param("reportId") String reportId);

    /** 举报列表（content_report 单表）：举报人/被举报人昵称由仓储回查 bs_user 拼装 */
    List<ContentReport> selectReports(@Param("status") String status, @Param("offset") int offset,
                                        @Param("limit") int limit);

    long countReports(@Param("status") String status);
}
