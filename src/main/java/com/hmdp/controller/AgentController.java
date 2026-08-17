package com.hmdp.controller;

import com.hmdp.dto.agent.AgentChatRequest;
import com.hmdp.service.IAgentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;

/**
 * AI 管家前端控制器
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    @Resource
    private IAgentService agentService;

    /**
     * AI 管家对话(SSE 流式),前端路径为 /api/agent/chat
     */
    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody AgentChatRequest request) {
        return agentService.chat(request);
    }
}
