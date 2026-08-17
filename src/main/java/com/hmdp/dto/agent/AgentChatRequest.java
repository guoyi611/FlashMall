package com.hmdp.dto.agent;

import lombok.Data;

/**
 * AI 管家对话 / 任务提交请求体(方案一 chat 与方案二 task 共用)
 */
@Data
public class AgentChatRequest {

    /**
     * 用户消息
     */
    private String message;

    /**
     * 会话 id,可选,不传则服务端新建会话
     */
    private String sessionId;
}
