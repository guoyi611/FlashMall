package com.hmdp.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentChatMessage {
    /** 消息角色:user / assistant(与 OpenAI 协议一致) */
    private String role;
    private String content;
}