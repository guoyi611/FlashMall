package com.hmdp.agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * DeepSeek(OpenAI 兼容协议)轻量客户端,零新增依赖。
 *
 * <p>仅实现本轮需要的能力:非流式 /chat/completions + JSON mode 强制结构化输出。
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

    /** 普通对话,返回 assistant 完整文本。 */
    public String chat(String system, String user) {
        return chat(system, user, temperature, false);
    }

    /** JSON mode 对话:强制 LLM 输出合法 JSON 对象,返回原始 JSON 字符串。 */
    public String chatJson(String system, String user) {
        return chat(system, user, temperature, true);
    }

    private String chat(String system, String user, double temp, boolean jsonMode) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
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

    private String post(String path, ObjectNode payload) {
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
            try (OutputStream os = conn.getOutputStream()) {
                os.write(objectMapper.writeValueAsBytes(payload));
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String resp = readAll(in);
            if (code >= 400) {
                throw new IOException("DeepSeek API " + code + ":" + resp);
            }
            return resp;
        } catch (IOException e) {
            throw new IllegalStateException("调用 DeepSeek 失败", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String readAll(InputStream in) throws IOException {
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
