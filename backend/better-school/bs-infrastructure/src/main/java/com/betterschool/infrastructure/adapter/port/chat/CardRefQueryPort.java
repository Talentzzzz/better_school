package com.betterschool.infrastructure.adapter.port.chat;

import com.betterschool.domain.chat.adapter.port.ICardRefQueryPort;
import com.betterschool.domain.community.adapter.repository.ICommunityRepository;
import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.community.model.valobj.PostStatusVO;
import com.betterschool.domain.trade.adapter.repository.ITradeRepository;
import com.betterschool.domain.trade.model.entity.ProductEntity;
import com.betterschool.domain.trade.model.valobj.ProductStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 业务卡片引用只读端口实现：只读 post / product 两域的读路径（含各自缓存口径），
 * 只把状态取回来交 chat 判定，不做「是否失效」的判断。
 * 落位在 adapter/port（不是 repository），跨域只读边的伸手点集中在这里
 */
@Component
@RequiredArgsConstructor
public class CardRefQueryPort implements ICardRefQueryPort {

    private final ICommunityRepository communityRepository;
    private final ITradeRepository tradeRepository;

    @Override
    public PostStatusVO findPostStatus(String postId) {
        PostEntity postEntity = communityRepository.findPostById(postId);
        return postEntity == null ? null : postEntity.getStatus();
    }

    @Override
    public ProductStatusVO findProductStatus(String productId) {
        ProductEntity productEntity = tradeRepository.findById(productId);
        return productEntity == null ? null : productEntity.getStatus();
    }
}
