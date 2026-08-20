package com.hmdp.service.impl;

import com.hmdp.agent.AgentPipeline;
import com.hmdp.dto.agent.AgentChatRequest;
import com.hmdp.service.IAgentService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 管家服务实现:仅做网关,校验 token 后在线程池内跑 AgentPipeline,直接以 SSE 流返回。
 * <p>LLM 调用与数据检索均在 Java 进程内完成,不再转发 Python AI 服务。</p>
 */
@Slf4j
@Service
public class AgentServiceImpl implements IAgentService {

    private static final long TIMEOUT_MS = 120_000L;

    @Resource
    private AgentPipeline pipeline;

    private final ExecutorService executor = Executors.newFixedThreadPool(20);

    @Override
    public SseEmitter chat(AgentChatRequest request) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        // UserHolder 绑定在 servlet 线程，线程池内取不到，必须在提交前解析出 userId 传下去
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        executor.execute(() -> {
            try {
                pipeline.run(request, userId, emitter);
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }
}
