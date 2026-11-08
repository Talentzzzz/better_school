package com.betterschool.usecase.trade;

import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import com.betterschool.domain.moderation.service.IModerationScreenService;
import com.betterschool.domain.moderation.service.IReviewService;
import com.betterschool.domain.trade.model.entity.ProductEntity;
import com.betterschool.domain.trade.service.product.IProductService;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.valobj.UserRoleVO;
import com.betterschool.types.concurrent.VirtualThreadFanout;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 二手交易发布用例编排：信用分门槛前置校验 + 同步初筛（标题+描述，命中即失败不落库）+ trade 域发布（商品）。
 * 草稿同轨：保存/编辑不做初筛（私有内容），提交与直接发布同门槛建检，初筛命中停留草稿
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradePublishCase {

    private final IProductService productService;
    private final ICreditAccountService creditAccountService;
    private final IModerationScreenService moderationScreenService;
    private final IReviewService reviewService;
    private final INotificationService notificationService;
    private final IUserRepository userRepository;
    private final VirtualThreadFanout fanout;

    /** 被拒商品的拒绝理由（content_review.reason），供详情/我的发布对卖家带出 */
    public String productRejectReason(String productId) {
        return reviewService.rejectReason(ReviewTargetTypeVO.PRODUCT, productId);
    }

    public String publishProduct(String userId, String title, String description, List<String> images, BigDecimal price,
                               String tradeLocation, String category) {
        creditAccountService.ensureOperable(userId);
        screenText(title, description);
        return productService.publishProduct(userId, title, description, images, price, tradeLocation, category);
    }

    /** 保存商品草稿：不做初筛、不做信用分门槛，字段可缺省 */
    public String saveProductDraft(String userId, String title, String description, List<String> images, BigDecimal price,
                                 String tradeLocation, String category) {
        return productService.saveProductDraft(userId, title, description, images, price, tradeLocation, category);
    }

    /** 编辑商品草稿：仅 DRAFT 可编辑（待审中需先撤回） */
    public void updateProductDraft(String userId, String productId, String title, String description, List<String> images,
                                   BigDecimal price, String tradeLocation, String category) {
        productService.updateProductDraft(userId, productId, title, description, images, price,
                tradeLocation, category);
    }

    /** 提交商品草稿：与直接发布同门槛校验，初筛命中停留草稿，通过进入待审 */
    public void submitProductDraft(String userId, String productId) {
        ProductEntity draftEntity = productService.getOwnedProductDraft(userId, productId);
        creditAccountService.ensureOperable(userId);
        validateProductDraftComplete(draftEntity);
        screenText(draftEntity.getTitle(), draftEntity.getDescription());
        productService.submitProductDraft(userId, productId);
    }

    /**
     * 上架后编辑商品（编辑语义）：先做归属与状态守卫（待审中不可编辑/被拒引导转草稿箱），
     * 新增文字内容过同步初筛，再由域层按"仅改价立即生效 / 改内容回待审"分流
     */
    public void editOnSaleProduct(String userId, String productId, String title, String description, List<String> images,
                                  BigDecimal price) {
        productService.getOwnedOnSaleProduct(userId, productId);
        screenText(title, description);
        productService.editOnSaleProduct(productId, title, description, images, price);
    }

    /**
     * 上架商品主动下架：ON_SALE → DRAFT 汇入草稿箱，可编辑后重新提交回待审；同事务退出搜索可见
     */
    public void takeDownProduct(String userId, String productId) {
        productService.takeDownProduct(userId, productId);
    }

    /**
     * 到期下架扫描（XXL-Job 每分钟）：进入待审满 30 天（expire_at，重提/改内容时刷新）的商品汇入草稿箱而非终态。
     * 条件 UPDATE 幂等，与下架并发无害。单发一批、批内扇出（宽度 = SCAN_BATCH_SIZE），项内失败只影响该项（下轮扫描补跑）
     */
    public void scanExpiredListings() {
        fanout.forEach("trade-expire-product-", productService.listExpiredProductIds(),
                productService::expireProductToDraft);
    }

    /**
     * 待审超 24h 提醒扫描（商品）：收件人 ADMIN+AUDITOR，每条待审每收件人只提醒一次（dedup_key 幂等）；
     * 逐收件人吞异常不影响扫描主流程，下轮扫描自动补发；不改状态，绝不自动裁决。
     * 扇出项 = 一条待审（其下逐收件人仍是串行循环，项内自行吞异常）
     */
    public void remindTradeReviewTimeouts() {
        List<String> reviewerIds = List.of(
                userRepository.listUserIdsByRole(UserRoleVO.ADMIN),
                userRepository.listUserIdsByRole(UserRoleVO.AUDITOR))
                .stream().flatMap(List::stream).distinct().toList();
        fanout.forEach("trade-remind-product-", productService.listReviewTimeoutProductIds(),
                productId -> remindReviewers(reviewerIds, "PRODUCT", "商品", productId));
    }

    private void remindReviewers(List<String> reviewerIds, String targetType, String targetLabel, String targetId) {
        for (String reviewerId : reviewerIds) {
            try {
                notificationService.sendSystem(reviewerId, NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.ORDER_EVENT, "审核提醒",
                        targetLabel + " " + targetId + " 已待审超过 24 小时，请及时处理", targetType, targetId,
                        NotificationTypeVO.ORDER_EVENT.name() + ":REVIEW_TIMEOUT:" + targetType + ":" + targetId + ":" + reviewerId);
            } catch (Exception e) {
                log.warn("[TradePublishCase] 待审超时提醒发送失败（下轮扫描补发） targetType={} targetId={} reviewerId={}",
                        targetType, targetId, reviewerId, e);
            }
        }
    }

    private void validateProductDraftComplete(ProductEntity draftEntity) {
        if (isBlank(draftEntity.getTitle()) || draftEntity.getPrice() == null
                || isBlank(draftEntity.getTradeLocation()) || draftEntity.getCategory() == null) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "草稿内容不完整，请补全标题、价格、交易地点与分类后再提交");
        }
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** 同步初筛（null 字段跳过：编辑场景 null = 不改该项，沿用原已审内容） */
    private void screenText(String title, String description) {
        if (title != null && moderationScreenService.screen(title)
                || description != null && moderationScreenService.screen(description)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "内容包含违规词汇，请修改后重试");
        }
    }
}
