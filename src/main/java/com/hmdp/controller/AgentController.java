package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.agent.ChatRequest;
import com.hmdp.service.IAgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;

/**
 * AI 智能管家接口
 * <p>
 * 聊天走 SSE 流式(方案一)，会话历史接口需登录后访问。
 */
@Slf4j
@RestController
@RequestMapping("/agent")
public class AgentController {

    @Resource
    private IAgentService agentService;

    /**
     * SSE 流式聊天，匿名也可对话
     */
    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody ChatRequest request, HttpServletResponse response) {
        // 关闭 nginx 缓冲，保证 SSE 实时推送；X-Accel-Buffering 仅对 nginx 生效
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        return agentService.chat(request);
    }

    /**
     * 我的会话列表
     */
    @GetMapping("/session/list")
    public Result listSessions() {
        return agentService.listSessions();
    }

    /**
     * 会话消息历史
     */
    @GetMapping("/session/{sessionId}/messages")
    public Result listMessages(@PathVariable("sessionId") String sessionId) {
        return agentService.listMessages(sessionId);
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/session/{sessionId}")
    public Result deleteSession(@PathVariable("sessionId") String sessionId) {
        return agentService.deleteSession(sessionId);
    }
}
