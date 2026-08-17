package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.agent.ChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 智能管家服务
 */
public interface IAgentService {

    /**
     * SSE 流式聊天：编排 5 个 Agent(意图识别→搜索→分析→推荐→汇总)，实时推送状态
     *
     * @param request 用户消息 + 可选 sessionId
     * @return SSE 事件流发射器
     */
    SseEmitter chat(ChatRequest request);

    /** 当前登录用户的会话列表 */
    Result listSessions();

    /** 会话消息历史 */
    Result listMessages(String sessionId);

    /** 删除会话 */
    Result deleteSession(String sessionId);
}
