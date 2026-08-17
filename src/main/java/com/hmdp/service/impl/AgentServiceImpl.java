package com.hmdp.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.agent.AgentChatRequest;
import com.hmdp.dto.agent.AgentErrorVO;
import com.hmdp.dto.agent.AgentMessageVO;
import com.hmdp.dto.agent.AgentSourceVO;
import com.hmdp.dto.agent.AgentStatusVO;
import com.hmdp.enums.AgentStatus;
import com.hmdp.service.IAgentService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 管家服务实现(骨架:先跑通 SSE 通道,Agent 编排逻辑后续替换)
 */
@Slf4j
@Service
public class AgentServiceImpl implements IAgentService {

    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    /**
     * 5 个 Agent 定义:{agentId, name, icon}
     */
    private static final String[][] AGENT_DEFS = {
            {"1", "意图识别Agent", "cpu"},
            {"2", "搜索Agent", "search"},
            {"3", "分析Agent", "data-line"},
            {"4", "推荐Agent", "medal-1"},
            {"5", "汇总Agent", "document-copy"},
    };

    @Override
    public SseEmitter chat(AgentChatRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);
        // 异步线程取不到 ThreadLocal,先在主线程取 userId
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        executor.execute(() -> doChat(request, userId, emitter));
        return emitter;
    }

    private void doChat(AgentChatRequest request, Long userId, SseEmitter emitter) {
        try {
            if (request == null || StrUtil.isBlank(request.getMessage())) {
                send(emitter, "error", new AgentErrorVO("AGENT_BAD_REQUEST", "消息不能为空"));
                emitter.complete();
                return;
            }

            // 1. 会话建立
            String sessionId = StrUtil.isNotBlank(request.getSessionId())
                    ? request.getSessionId() : "s_" + IdUtil.simpleUUID();
            send(emitter, "session", Collections.singletonMap("sessionId", sessionId));

            // 2. 编排各 Agent(骨架:顺序 running -> done)
            for (String[] def : AGENT_DEFS) {
                sendAgent(emitter, def, AgentStatus.RUNNING, null, "执行中...");
                Thread.sleep(300);
                sendAgent(emitter, def, AgentStatus.DONE, 300L, "已完成(骨架占位)");
            }

            // 3. 最终回复
            AgentMessageVO message = new AgentMessageVO();
            message.setContent("**已收到您的需求：**\n\n" + request.getMessage()
                    + "\n\n> 以上为骨架占位回复，后续由汇总Agent生成。");
            message.setSources(Collections.<AgentSourceVO>emptyList());
            send(emitter, "message", message);

            // 4. 本轮结束
            send(emitter, "done", Collections.emptyMap());
            emitter.complete();
        } catch (Exception e) {
            log.error("agent chat error", e);
            try {
                send(emitter, "error", new AgentErrorVO("AGENT_INTERNAL_ERROR", "服务异常"));
            } catch (IOException ignored) {
                // 客户端已断开,忽略
            }
            emitter.completeWithError(e);
        }
    }

    private void sendAgent(SseEmitter emitter, String[] def, AgentStatus status, Long duration, String description)
            throws IOException {
        AgentStatusVO vo = new AgentStatusVO();
        vo.setAgentId(Integer.valueOf(def[0]));
        vo.setName(def[1]);
        vo.setIcon(def[2]);
        vo.setStatus(status);
        vo.setDuration(duration);
        vo.setDescription(description);
        send(emitter, "agent", vo);
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }
}
