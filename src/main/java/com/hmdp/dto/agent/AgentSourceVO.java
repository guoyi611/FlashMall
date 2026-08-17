package com.hmdp.dto.agent;

import lombok.Data;

/**
 * 推荐来源(最终回复里可点击的链接)
 */
@Data
public class AgentSourceVO {

    /**
     * 来源名称,如商户名
     */
    private String name;

    /**
     * 跳转链接,如 /shop-detail.html?id=1
     */
    private String url;
}
