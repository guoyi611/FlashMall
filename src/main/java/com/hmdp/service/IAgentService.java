package com.hmdp.service;

import com.hmdp.dto.agent.AgentChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 管家服务
 */
public interface IAgentService {

    /**
     * 发起对话,返回 SSE 事件流。
     * <p>事件顺序:session -> agent(running/done 成对) -> message -> done,异常时发 error。</p>
     *
     * @param request 用户消息与会话 id(会话 id 可空)
     * @return SseEmitter 事件流
     */
    SseEmitter chat(AgentChatRequest request);
}
