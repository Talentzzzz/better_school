-- Redis + MQ 逐条扣减：只有「查余量 + 递减」，没有合并窗口
-- KEYS[1] 桶 key   lt:inv:bucket:<skuId>:<bucketId>
-- ARGV[1] 扣减数量
--
-- 返回 0 = 成功；-1 = 桶不存在；-2 = 余量不足（无副作用）
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then
    return -1
end
local quantity = tonumber(ARGV[1])
if stock < quantity then
    return -2
end
redis.call('DECRBY', KEYS[1], quantity)
return 0
