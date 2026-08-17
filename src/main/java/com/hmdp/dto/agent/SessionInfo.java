package com.hmdp.dto.agent;

import lombok.Data;

/**
 * 会话概要信息
 */
@Data
public class SessionInfo {
    private String sessionId;
    /** 会话摘要(首条消息截断) */
    private String summary;
    /** 创建时间戳(ms) */
    private Long createTime;
}
