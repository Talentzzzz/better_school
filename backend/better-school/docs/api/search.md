# 站内搜索模块接口

四个搜索面共用一套口径：**免登录浏览**（登录时附加个性化字段）；`keyword` 必填非空（空关键词 400）；分页 `?page=1&pageSize=10`，pageSize ≤ 50。ES 只负责命中 + 排序，展示字段由搜索服务按 id 从 MySQL 反查组装（相关度顺序保持）；只搜审核通过且对外可见的内容。

## GET /posts?keyword
搜帖子。返回帖子摘要分页（同社区列表口径：不暴露浏览量；登录时带 `liked` = 当前用户点赞态；`content` 供客户端截断展示）。

## GET /products?keyword
搜闲置商品。返回 `ProductResponseDTO` 分页（与商品详情同构，含卖家冗余昵称 / 头像）。

## GET /errands?keyword
搜跑腿订单。返回 `ErrandOrderResponseDTO` 分页（单人单口径）。

## GET /users?keyword
搜用户。返回 `data.list = [{userId, nickname, avatar}]`，仅公开资料三字段。
