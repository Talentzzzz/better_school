package com.betterschool.domain.chat.service.query;

import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.valobj.CardRefTypeVO;
import com.betterschool.domain.chat.model.valobj.MsgTypeVO;
import com.betterschool.domain.chat.model.valobj.RoomListItemVO;

/**
 * 会话列表预览渲染器（票 02 预览去副本）：预览不再落 user_room 副本，读时按 message 真相现算——
 * message 是预览唯一真相，撤回即时反映、永不漂移。渲染规则（类型与文案在此单点收敛）：
 * RECALLED→[已撤回] / IMAGE→[图片] / CARD→按 ref_type 出类型标签（不查标题）/ TEXT→原文截 200
 */
public final class RoomPreviewRenderer {

    /** 会话列表预览文案截断长度 */
    public static final int PREVIEW_MAX_LENGTH = 200;

    public static final String PREVIEW_RECALLED = "[已撤回]";
    public static final String PREVIEW_IMAGE = "[图片]";
    public static final String PREVIEW_PRODUCT = "[商品]";
    public static final String PREVIEW_POST = "[帖子]";
    public static final String PREVIEW_ERRAND = "[跑腿卡片]";

    private RoomPreviewRenderer() {
    }

    /**
     * 把 message 真相渲染进列表项（lastMsgType + lastMsgPreview 一次填齐）。
     *
     * @param room    待装配的列表项
     * @param message 会话最新一条消息（room.last_seq 点查所得；null = 会话尚无消息，预览为空）
     */
    public static void renderInto(RoomListItemVO room, MessageEntity message) {
        room.setLastMsgType(message == null ? null : message.getMsgType());
        room.setLastMsgPreview(render(message));
    }

    /** 按 message 真相渲染预览文案（null message → null 预览） */
    public static String render(MessageEntity message) {
        if (message == null) {
            return null;
        }
        if (message.isRecalled()) {
            return PREVIEW_RECALLED;
        }
        return switch (message.getMsgType()) {
            case TEXT -> truncate(message.getContent());
            case IMAGE -> PREVIEW_IMAGE;
            case CARD -> renderCard(message.getRefType());
        };
    }

    private static String renderCard(CardRefTypeVO refType) {
        return switch (refType) {
            case PRODUCT -> PREVIEW_PRODUCT;
            case POST -> PREVIEW_POST;
            case ERRAND -> PREVIEW_ERRAND;
        };
    }

    private static String truncate(String content) {
        if (content == null) {
            return null;
        }
        return content.length() > PREVIEW_MAX_LENGTH ? content.substring(0, PREVIEW_MAX_LENGTH) : content;
    }
}
