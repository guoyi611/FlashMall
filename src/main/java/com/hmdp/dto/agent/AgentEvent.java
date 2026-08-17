package com.hmdp.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 状态事件，对应 SSE 的 agent 事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent {
    private Integer agentId;
    private String name;
    /** 前端图标 key */
    private String icon;
    /** pending | running | done | error */
    private String status;
    /** 执行说明/结果摘要 */
    private String description;
    /** 执行耗时(ms)，仅 done 时有效 */
    private Long duration;
    /** 可选，Agent 产出 */
    private String output;
}
