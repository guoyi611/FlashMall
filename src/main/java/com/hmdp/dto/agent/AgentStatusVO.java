package com.hmdp.dto.agent;

import com.hmdp.enums.AgentStatus;
import lombok.Data;

/**
 * Agent 状态(SSE agent 事件 data / 任务轮询 agents 数组元素)
 */
@Data
public class AgentStatusVO {

    /**
     * 编排器中的 Agent 序号
     */
    private Integer agentId;

    /**
     * 展示名称,如"意图识别Agent"
     */
    private String name;

    /**
     * 前端图标 key(cpu/search/data-line/medal-1/document-copy)
     */
    private String icon;

    /**
     * 执行状态
     */
    private AgentStatus status;

    /**
     * 当前执行说明 / 结果摘要
     */
    private String description;

    /**
     * 执行耗时(ms),仅 done 时有效
     */
    private Long duration;

    /**
     * Agent 产出,可选
     */
    private String output;
}
