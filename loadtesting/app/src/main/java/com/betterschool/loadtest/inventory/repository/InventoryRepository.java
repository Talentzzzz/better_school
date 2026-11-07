package com.betterschool.loadtest.inventory.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 所有落库动作集中在这里：三种策略共用同一张 inventory_ledger，SQL 差异就是性能差异本身。
 * 刻意不用 MyBatis —— 压测要的是「基础设施开销」，ORM 的额外一层会污染对照结果。
 */
@Repository
public class InventoryRepository {

    private final JdbcTemplate jdbc;

    public InventoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 初始化 / 重置

    public void ensureLedger(long skuId, long stock) {
        jdbc.update("""
                INSERT IGNORE INTO inventory_ledger (sku_id, total_qty, surplus_qty, waiting_qty, issued_qty)
                VALUES (?, ?, ?, 0, 0)
                """, skuId, stock, stock);
    }

    public void ensureBuckets(long skuId, int bucketCount, long stock) {
        long base = bucketCount <= 0 ? 0 : stock / bucketCount;
        long rest = bucketCount <= 0 ? 0 : stock - base * bucketCount;
        for (int i = 0; i < bucketCount; i++) {
            long qty = base + (i == bucketCount - 1 ? rest : 0);
            jdbc.update("""
                    INSERT IGNORE INTO inventory_bucket (sku_id, bucket_id, redis_qty) VALUES (?, ?, ?)
                    """, skuId, i, qty);
        }
    }

    public int countBuckets(long skuId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_bucket WHERE sku_id = ?", Integer.class, skuId);
        return n == null ? 0 : n;
    }

    public List<BucketRow> buckets(long skuId) {
        return jdbc.query("""
                        SELECT bucket_id, redis_qty FROM inventory_bucket WHERE sku_id = ? ORDER BY bucket_id
                        """,
                (rs, i) -> new BucketRow(rs.getInt("bucket_id"), rs.getLong("redis_qty")), skuId);
    }

    public void activateLedger(long skuId, long stock) {
        jdbc.update("""
                INSERT INTO inventory_ledger (sku_id, total_qty, surplus_qty, waiting_qty, issued_qty)
                VALUES (?, ?, ?, 0, 0)
                ON DUPLICATE KEY UPDATE total_qty = ?, surplus_qty = ?, waiting_qty = 0, issued_qty = 0
                """, skuId, stock, stock, stock, stock);
    }

    public void replaceBuckets(long skuId, int bucketCount, long stock) {
        jdbc.update("DELETE FROM inventory_bucket WHERE sku_id = ?", skuId);
        ensureBuckets(skuId, bucketCount, stock);
    }

    public void deleteRunData(long skuId) {
        jdbc.update("DELETE FROM inventory_deduct_detail WHERE sku_id = ?", skuId);
        jdbc.update("DELETE FROM inventory_order WHERE sku_id = ?", skuId);
    }

    // ---------------------------------------------------------------- db 策略

    /** 单行行锁：命中条件即扣减，affected = 0 表示卖完（不是异常） */
    public boolean deductLedgerRow(long skuId, int qty) {
        int rows = jdbc.update("""
                UPDATE inventory_ledger
                   SET surplus_qty = surplus_qty - ?, issued_qty = issued_qty + ?
                 WHERE sku_id = ? AND surplus_qty >= ?
                """, qty, qty, skuId, qty);
        return rows == 1;
    }

    public void insertOrder(String orderNo, long skuId, int qty, String strategy) {
        jdbc.update("""
                INSERT INTO inventory_order (order_no, sku_id, qty, strategy) VALUES (?, ?, ?, ?)
                """, orderNo, skuId, qty, strategy);
    }

    // ---------------------------------------------------------------- redis_mq / merge 共用

    /** 消费端真正扣台账（redis_mq：每条一次） */
    public void applyIssued(long skuId, int qty) {
        jdbc.update("""
                UPDATE inventory_ledger SET surplus_qty = surplus_qty - ?, issued_qty = issued_qty + ?
                 WHERE sku_id = ?
                """, qty, qty, skuId);
    }

    /** 合并：预扣量从余量移入在途量 */
    public void applyWaiting(long skuId, long qty) {
        jdbc.update("""
                UPDATE inventory_ledger SET surplus_qty = surplus_qty - ?, waiting_qty = waiting_qty + ?
                 WHERE sku_id = ?
                """, qty, qty, skuId);
    }

    public void decBucketCopy(long skuId, int bucketId, long qty) {
        jdbc.update("""
                UPDATE inventory_bucket SET redis_qty = redis_qty - ? WHERE sku_id = ? AND bucket_id = ?
                """, qty, skuId, bucketId);
    }

    // ---------------------------------------------------------------- merge 明细

    public void insertDetail(long skuId, int bucketId, int qty, String orderNo) {
        jdbc.update("""
                INSERT INTO inventory_deduct_detail (sku_id, bucket_id, qty, biz_type, biz_no, state)
                VALUES (?, ?, ?, 'DEDUCT', ?, 0)
                """, skuId, bucketId, qty, orderNo);
    }

    public Timestamp oldestCell(long skuId, int bucketId) {
        try {
            return jdbc.queryForObject("""
                    SELECT MIN(create_time) FROM inventory_deduct_detail
                     WHERE sku_id = ? AND bucket_id = ? AND state = 0
                    """, Timestamp.class, skuId, bucketId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public CellStat cellStat(long skuId, int bucketId, Timestamp cell) {
        return jdbc.queryForObject("""
                        SELECT COUNT(*) AS cnt, COALESCE(SUM(qty), 0) AS qty, COALESCE(MAX(id), 0) AS max_id
                          FROM inventory_deduct_detail
                         WHERE sku_id = ? AND bucket_id = ? AND state = 0 AND create_time = ?
                        """,
                (rs, i) -> new CellStat(rs.getLong("cnt"), rs.getLong("qty"), rs.getLong("max_id")),
                skuId, bucketId, cell);
    }

    /**
     * 只翻这一格：带上 id <= maxId 的快照边界，避免「统计完又新进来的行」把 affected 撑大。
     */
    public int markMerged(long skuId, int bucketId, Timestamp cell, long maxId) {
        return jdbc.update("""
                UPDATE inventory_deduct_detail SET state = 1, update_time = NOW(3)
                 WHERE sku_id = ? AND bucket_id = ? AND state = 0 AND create_time = ? AND id <= ?
                """, skuId, bucketId, cell, maxId);
    }

    public boolean hasPending(long skuId, int bucketId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM inventory_deduct_detail
                               WHERE sku_id = ? AND bucket_id = ? AND state = 0 LIMIT 1)
                """, Boolean.class, skuId, bucketId);
        return Boolean.TRUE.equals(exists);
    }

    public List<BucketRef> staleBuckets(int staleSeconds, int limit) {
        String sql = """
                SELECT sku_id, bucket_id FROM inventory_deduct_detail
                 WHERE state = 0 AND create_time < DATE_SUB(NOW(), INTERVAL %d SECOND)
                 GROUP BY sku_id, bucket_id LIMIT ?
                """.formatted(staleSeconds);
        return jdbc.query(sql, (rs, i) -> new BucketRef(rs.getLong("sku_id"), rs.getInt("bucket_id")), limit);
    }

    public PendingStat pendingStat(long skuId) {
        return jdbc.queryForObject("""
                        SELECT COUNT(*) AS cnt, COALESCE(SUM(qty), 0) AS qty
                          FROM inventory_deduct_detail WHERE sku_id = ? AND state = 0
                        """,
                (rs, i) -> new PendingStat(rs.getLong("cnt"), rs.getLong("qty")), skuId);
    }

    public long mergedRows(long skuId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_deduct_detail WHERE sku_id = ? AND state = 1
                """, Long.class, skuId);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- 统计

    public LedgerRow ledger(long skuId) {
        try {
            return jdbc.queryForObject("""
                            SELECT total_qty, surplus_qty, waiting_qty, issued_qty
                              FROM inventory_ledger WHERE sku_id = ?
                            """,
                    (rs, i) -> new LedgerRow(rs.getLong("total_qty"), rs.getLong("surplus_qty"),
                            rs.getLong("waiting_qty"), rs.getLong("issued_qty")), skuId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public long orderCount(long skuId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_order WHERE sku_id = ?", Long.class, skuId);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- record

    public record LedgerRow(long total, long surplus, long waiting, long issued) {
    }

    public record BucketRow(int bucketId, long redisQty) {
    }

    public record CellStat(long rows, long qty, long maxId) {
    }

    public record BucketRef(long skuId, int bucketId) {
    }

    public record PendingStat(long rows, long qty) {
    }
}
