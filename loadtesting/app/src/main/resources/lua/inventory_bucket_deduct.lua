-- 合并扣减：桶余量原子「查 + 扣 + 决定要不要叫醒合并」（与主项目 inventory_bucket_deduct.lua 同构）
-- KEYS[1] 桶 key            lt:inv:bucket:<skuId>:<bucketId>
-- KEYS[2] 合并去重 key      lt:inv:merge:<skuId>:<bucketId>
-- ARGV[1] 扣减数量
-- ARGV[2] 去重窗口毫秒（固定窗口，从第一笔算）
--
-- 返回 {code, notify}
--   code   0 = 扣减成功；-1 = 桶不存在（未预热）；-2 = 余量不足（无副作用）
--   notify 1 = 这一笔要发合并消息；0 = 窗口内已有消息
--
-- 为什么「扣成功才 SETNX」：扣失败的请求不会落明细，发消息也是空跑。
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then
    return {-1, 0}
end
local quantity = tonumber(ARGV[1])
if stock < quantity then
    return {-2, 0}
end
redis.call('DECRBY', KEYS[1], quantity)
local notify = redis.call('SET', KEYS[2], '1', 'PX', ARGV[2], 'NX')
return {0, notify and 1 or 0}
