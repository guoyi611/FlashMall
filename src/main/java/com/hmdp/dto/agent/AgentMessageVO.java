package com.hmdp.dto.agent;

import lombok.Data;

import java.util.List;

/**
 * SSE message 事件 data:最终回复
 */
@Data
public class AgentMessageVO {

    /**
     * markdown 文本
     */
    private String content;

    /**
     * 推荐来源
     */
    private List<AgentSourceVO> sources;
}
