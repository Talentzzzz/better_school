package com.betterschool.domain.chat.adapter.port;

import com.betterschool.domain.community.model.valobj.PostStatusVO;
import com.betterschool.domain.trade.model.valobj.ProductStatusVO;

/**
 * 业务卡片引用只读查询端口（chat 域的跨域只读声明）：只取引用对象的当前状态，不存在返回 null。
 * 「哪些状态算已失效」的判据留在 chat 侧（发送用例的载荷校验），本端口不做任何判定，
 * 也不返回对象本体——消息体只存引用标识不存快照（见 CONTEXT「业务卡片」）
 */
public interface ICardRefQueryPort {

    /** 帖子状态；不存在返回 null（走 post 域自己的读路径，详情缓存口径与 chat 之外一致） */
    PostStatusVO findPostStatus(String postId);

    /** 商品状态；不存在返回 null（业务 id 是 UUID 字符串） */
    ProductStatusVO findProductStatus(String productId);
}
