package com.betterschool.api;

import com.betterschool.api.dto.PostSummaryResponseDTO;
import com.betterschool.api.dto.ProductResponseDTO;
import com.betterschool.api.dto.UserSearchResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;

/**
 * 站内搜索契约：ES 全文检索，仅返回公开可见实体；页面上方结果由各实体详情接口进入
 */
public interface ISearchService {

    /** 帖子搜索（title/content，ik 分词） */
    Response<PageResult<PostSummaryResponseDTO>> searchPosts(String keyword, int page, int pageSize);

    /** 商品搜索（title/description/tradeLocation） */
    Response<PageResult<ProductResponseDTO>> searchProducts(String keyword, int page, int pageSize);

    /** 用户搜索（nickname，仅 NORMAL 用户） */
    Response<PageResult<UserSearchResponseDTO>> searchUsers(String keyword, int page, int pageSize);
}
