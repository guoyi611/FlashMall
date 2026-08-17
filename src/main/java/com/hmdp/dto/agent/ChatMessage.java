package com.hmdp.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话内一条消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    /** user | assistant */
    private String role;
    private String content;
    /** 时间戳(ms) */
    private Long time;
}
