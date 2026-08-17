package com.hmdp.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE error 事件 data / 错误信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentErrorVO {

    /**
     * 错误码,如 AGENT_xxx
     */
    private String code;

    /**
     * 错误信息(前端直接展示给用户)
     */
    private String message;
}
