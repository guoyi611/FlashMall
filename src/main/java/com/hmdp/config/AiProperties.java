package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 智能管家配置项，对应 application.yaml 中 hmdp.ai 前缀
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.ai")
public class AiProperties {
    /** 大模型服务基础地址，OpenAI 兼容协议 */
    private String baseUrl = "https://api.deepseek.com";
    /** API Key，留空时智能管家会提示未配置 */
    private String apiKey = "";
    /** 模型名称 */
    private String model = "deepseek-chat";
    /** 单次调用超时时间(ms) */
    private int timeout = 60000;
}
