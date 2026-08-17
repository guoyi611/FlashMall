package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.agent.AgentChatRequest;
import com.hmdp.dto.agent.AgentErrorVO;
import com.hmdp.service.IAgentService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 管家服务实现:作为网关,验 token 后将请求转发到 Python AI 服务,并桥接其 SSE 流。
 */
@Slf4j
@Service
public class AgentServiceImpl implements IAgentService {

    private static final long TIMEOUT_MS = 120_000L;

    @Value("${agent.service-url:http://127.0.0.1:8000}")
    private String serviceUrl;

    @Resource
    private ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newFixedThreadPool(20);

    @Override
    public SseEmitter chat(AgentChatRequest request) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        // 异步线程拿不到 ThreadLocal,先在主线程取 userId
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        executor.execute(() -> forward(request, userId, emitter));
        return emitter;
    }

    private void forward(AgentChatRequest request, Long userId, SseEmitter emitter) {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(buildBody(request, userId));
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                send(emitter, "error", new AgentErrorVO("AGENT_UPSTREAM_ERROR", "AI 服务异常(" + code + ")"));
                emitter.complete();
                return;
            }

            // 流式读 Python 的 SSE,按空行分块转发
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder block = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        forwardBlock(emitter, block.toString());
                        block.setLength(0);
                    } else {
                        block.append(line).append('\n');
                    }
                }
                if (block.length() > 0) {
                    forwardBlock(emitter, block.toString());
                }
            }
            emitter.complete();
        } catch (Exception e) {
            log.error("agent forward error", e);
            try {
                send(emitter, "error", new AgentErrorVO("AGENT_INTERNAL_ERROR", "服务异常"));
            } catch (IOException ignored) {
                // 客户端已断开,忽略
            }
            // 已推送 error 事件,正常结束即可,避免再把异常抛到 servlet 层
            emitter.complete();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(String body) throws IOException {
        URL url = new URL(serviceUrl + "/chat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout((int) TIMEOUT_MS);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    private String buildBody(AgentChatRequest request, Long userId) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("message", request.getMessage());
        body.put("sessionId", request.getSessionId());
        body.put("userId", userId);
        return objectMapper.writeValueAsString(body);
    }

    private void forwardBlock(SseEmitter emitter, String block) throws IOException {
        String event = null;
        StringBuilder data = new StringBuilder();
        for (String line : block.split("\n")) {
            if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (event == null || data.length() == 0) {
            return;
        }
        // 解析成对象再转发,避免字符串被二次转义
        Object parsed = objectMapper.readValue(data.toString(), Object.class);
        emitter.send(SseEmitter.event().name(event).data(parsed));
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }
}
