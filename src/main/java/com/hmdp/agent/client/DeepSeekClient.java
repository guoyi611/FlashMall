package com.hmdp.agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.dto.agent.AgentChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * DeepSeek(OpenAI 兼容协议)轻量客户端,零新增依赖。
 *
 * <p>仅实现本轮需要的能力:非流式 /chat/completions + JSON mode 强制结构化输出。
 * 多轮历史通过 {@link AgentChatMessage} 列表重载,messages 数组 = system + history + 末尾追加 user。
 * 后续如需 token 级流式,在此追加流式读 SSE 的方法即可。</p>
 */
@Slf4j
@Component
public class DeepSeekClient {

    @Value("${agent.llm.base-url}")
    private String baseUrl;

    @Value("${agent.llm.api-key}")
    private String apiKey;

    @Value("${agent.llm.model}")
    private String model;

    @Value("${agent.llm.temperature}")
    private double temperature;

    @Value("${agent.llm.timeout-ms}")
    private int timeoutMs;

    @Resource
    private ObjectMapper objectMapper;

    /** 普通对话(单轮,无历史),返回 assistant 完整文本。 */
    public String chat(String system, String user) {
        return chat(system, null, user, temperature, false);
    }

    /** JSON mode 对话(单轮,无历史):强制 LLM 输出合法 JSON 对象,返回原始 JSON 字符串。 */
    public String chatJson(String system, String user) {
        return chat(system, null, user, temperature, true);
    }

    /** 普通对话,携带多轮历史(不含当前 user,当前 user 单独传)。 */
    public String chat(String system, List<AgentChatMessage> history, String user) {
        return chat(system, history, user, temperature, false);
    }

    /** JSON mode 对话,携带多轮历史。 */
    public String chatJson(String system, List<AgentChatMessage> history, String user) {
        return chat(system, history, user, temperature, true);
    }

    /** 核心实现:messages = system + history + 末尾追加 user。 */
    private String chat(String system, List<AgentChatMessage> history, String user, double temp, boolean jsonMode) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        if (history != null) {
            for (AgentChatMessage m : history) {
                messages.addObject().put("role", m.getRole()).put("content", m.getContent());
            }
        }
        messages.addObject().put("role", "user").put("content", user);
        payload.put("temperature", temp);
        payload.put("stream", false);
        if (jsonMode) {
            payload.putObject("response_format").put("type", "json_object");
        }

        String resp = post("/chat/completions", payload);
        try {
            JsonNode root = objectMapper.readTree(resp);
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null) {
                throw new IllegalStateException("LLM 响应缺少 content:" + resp);
            }
            return content.trim();
        } catch (IOException e) {
            throw new IllegalStateException("解析 LLM 响应失败", e);
        }
    }

    /**
     * POST 请求。
     * <p>每次发送 {@code Connection: close} 强制新建连接,规避 keep-alive 复用服务端已关闭的旧连接
     * (SocketException: Unexpected end of file from server)。连接层异常与 5xx 重试 1 次,4xx 直接抛。
     * 若网络环境走代理且该头有副作用,去掉后依赖 {@code conn.disconnect()} 并接受偶发重试。</p>
     */
    private String post(String path, ObjectNode payload) {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(baseUrl + path);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Connection", "close");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(objectMapper.writeValueAsBytes(payload));
                }
                int code = conn.getResponseCode();
                // 5xx 视为服务端临时故障,首次遇到直接丢连接重试
                if (code >= 500 && attempt < 1) {
                    log.warn("DeepSeek API {} 服务端异常,重试", code);
                    continue;
                }
                InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String resp = readAll(in);
                if (code >= 400) {
                    throw new IOException("DeepSeek API " + code + ":" + resp);
                }
                return resp;
            } catch (IOException e) {
                last = e;
                if (attempt < 1) {
                    log.warn("调用 DeepSeek 异常,重试:{}", e.getMessage());
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw new IllegalStateException("调用 DeepSeek 失败", last);
    }

    private String readAll(InputStream in) throws IOException {
        // 4xx 且服务端无响应体时 getErrorStream() 可能为 null,提前返回避免 NPE
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
