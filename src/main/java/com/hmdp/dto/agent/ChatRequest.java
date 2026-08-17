package com.hmdp.dto.agent;

import lombok.Data;

/**
 * AI 管家聊天请求体
 */
@Data
public class ChatRequest {
    /** 用户消息 */
    private String message;
    /** 会话id，可选，不传则服务端新建 */
    private String sessionId;
}
