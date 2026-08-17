package com.hmdp.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Agent 执行状态(SSE agent 事件 / 任务轮询 agents 数组共用)
 *
 * <p>序列化为小写,与前端 agent.html 约定一致。</p>
 */
public enum AgentStatus {

    PENDING("pending"),
    RUNNING("running"),
    DONE("done"),
    ERROR("error");

    private final String value;

    AgentStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
