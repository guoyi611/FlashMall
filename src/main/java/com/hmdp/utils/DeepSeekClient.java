package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * DeepSeek(OpenAI 兼容) HTTP 客户端，基于 Hutool 实现，无需额外依赖
 */
@Slf4j
@Component
public class DeepSeekClient {

    @Resource
    private AiProperties aiProperties;

    /**
     * 聊天补全，返回模型文本内容
     *
     * @param messages OpenAI messages 数组
     * @param jsonMode 是否要求返回 JSON 对象(deepseek 支持 response_format)
     */
    public String chat(List<JSONObject> messages, boolean jsonMode) {
        String url = StrUtil.removeSuffix(aiProperties.getBaseUrl(), "/") + "/chat/completions";
        JSONObject body = new JSONObject();
        body.set("model", aiProperties.getModel());
        body.set("messages", messages);
        body.set("stream", false);
        body.set("temperature", 0.7);
        if (jsonMode) {
            JSONObject fmt = new JSONObject();
            fmt.set("type", "json_object");
            body.set("response_format", fmt);
        }
        String respBody;
        try {
            HttpResponse resp = HttpRequest.post(url)
                    .header("Authorization", "Bearer " + aiProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(aiProperties.getTimeout())
                    .execute();
            respBody = resp.body();
            if (!resp.isOk()) {
                log.error("AI 服务调用失败, status={}, body={}", resp.getStatus(), respBody);
                throw new RuntimeException("AI 服务调用失败(" + resp.getStatus() + ")");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI 服务网络异常", e);
            throw new RuntimeException("AI 服务网络异常");
        }
        JSONObject json = JSONUtil.parseObj(respBody);
        JSONArray choices = json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            log.error("AI 服务返回异常: {}", respBody);
            throw new RuntimeException("AI 服务返回异常");
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        return message == null ? "" : StrUtil.nullToEmpty(message.getStr("content")).trim();
    }

    /**
     * 解析模型返回的 JSON，兼容 markdown 代码块包裹的情况
     */
    public JSONObject parseJsonObject(String content) {
        String json = content;
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = content.substring(start, end + 1);
        }
        return JSONUtil.parseObj(json);
    }
}
