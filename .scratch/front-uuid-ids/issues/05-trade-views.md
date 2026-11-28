# 05: 交易视图簇改字符串 ID（商品 / 订单 / 收银台）

**What to build:** 商品详情（收藏 / 联系卖家带商品卡片 / 立即购买 / 下架重上）、订单详情（取消 / 确认收货 / 标记交付 / 卖家主页 / 联系买家）、订单列表、收银台、市集推荐页，ID 全部按字符串透传。

**Blocked by:** 01, 02

**Status:** ready-for-agent

票 01 之后 `Product.productId` / `ProductOrder.orderId` 等已是 `string`，`createProduct` / `createProductOrder` 返回 `string`；票 02 之后 `/product/:id`、`/idle/order/:id`、`/trade/order/:id` 不限数字。本票只改视图层。

## 1. `front/src/views/idle/ProductDetailView.vue`（本簇主战场）

- `const productId = String(route.params.id ?? '')`（禁止 `Number()`）。
- `isSeller` 的 `product.value?.userId === auth.userId` 两侧同为字符串。
- `createDirectRoom(product.value.userId, 'PRODUCT')` 与 `router.push(\`/chat/${room.roomId}?card=PRODUCT:${productId}\`)` 原样（`?card` 的解析规则在票 06 的聊天页，本票只负责把 UUID 拼进去）。
- `const orderId = await createProductOrder(productId)` 现在是 `string` → `router.push(\`/idle/order/${orderId}\`)` 不用改。
- 模板 `:seed="product.productId"`、`/user/${product.userId}` 保持。

## 2. `front/src/views/idle/ProductOrderView.vue`

- `const orderId = String(route.params.id ?? '')`。
- `order.value.buyerId === auth.userInfo?.userId` / `sellerId` 比对：字符串严格相等，关系不变。
- `markDeliveredProduct(orderId)` / `confirmProductOrder(orderId)` / `cancelProductOrder(orderId)` / `getProductOrder(orderId)` 透传。
- `router.push(\`/product/${order.productId}\`)`、`#{{ order.orderId }}` 展示保持（不截断、不改版式；露出 UUID 是预期的后端口径）。

## 3. 其余文件

- `front/src/views/me/MyOrdersView.vue`：`:key="o.orderId"`、`/idle/order/${o.orderId}` 保持，预计零改动。
- `front/src/views/pay/CashierView.vue`：`payNo` 已是 `String(route.params.payNo)`；成功后 `router.back()` / `/me/orders` 不涉及业务 ID。审计即可。
- `front/src/views/MarketView.vue`、`front/src/views/idle/ProductPublishView.vue`、`front/src/components/ProductCard.vue`、`front/src/components/MarketEntryRow.vue`、`front/src/utils/orderAction.ts`：审计。`ProductPublishView` 的 `Number(price.value)` 是**价格**，保持不动；`const id = await createProduct(...)` 现在推到 `string`，`router.replace(\`/product/${id}\`)` 不用改。

## 不碰

`front/src/views/post/*`、`views/AuthorProfileView.vue`（票 03/04）、`views/chat/*`（票 06）、`views/marketing/*`（票 07）。不改版式、文案、交互；不给 ID 做截断或美化。

## 验收（静态检索自查，**不跑构建 / 类型检查 / 测试**）

- [ ] `front/src/views/idle/` 中 `Number(` 只剩 `ProductPublishView` 的价格解析（`Number(price.value)`）；`Id\s*[:?]\s*number` 命中 0。
- [ ] `ProductDetailView`：路由参数 → 详情 → 收藏 / 建会话（带 `?card=PRODUCT:<uuid>`）/ 建订单 → 跳订单详情，五条链实参类型一致。
- [ ] `ProductOrderView`：角色判定（买家/卖家）两侧同为字符串；三个资金动作传的是同一个 `orderId` 字符串。
- [ ] `git diff --stat` 只包含本票文件。
