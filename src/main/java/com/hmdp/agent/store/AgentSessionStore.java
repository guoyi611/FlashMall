package com.hmdp.agent.store;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.agent.AgentChatMessage;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent 多轮会话的 Redis 存储。
 * <p>
 * 两类 key(命名沿用 {@code 领域:实体:字段} 冒号分隔约定):
 * <ul>
 *   <li>{@code agent:session:<id>:messages}  — List,对话历史,每条为 AgentChatMessage JSON,RPUSH + LTRIM 截断</li>
 *   <li>{@code agent:session:<id>:meta}      — Hash,owner(归属用户id) / lastIntent(上次推荐意图 JSON 字符串)</li>
 * </ul>
 * 所有写操作滑动续期 24h。
 * </p>
 */
@Component
public class AgentSessionStore {

    private static final String MSGS_KEY_SUFFIX = ":messages";
    private static final String META_KEY_SUFFIX = ":meta";
    private static final String FIELD_OWNER = "owner";
    private static final String FIELD_LAST_INTENT = "lastIntent";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 读取整段对话历史。 */
    public List<AgentChatMessage> loadMessages(String sessionId) {
        String key = RedisConstants.AGENT_SESSION_KEY + sessionId + MSGS_KEY_SUFFIX;
        List<String> jsonList = stringRedisTemplate.opsForList().range(key, 0, -1);
        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();
        }
        return jsonList.stream()
                .map(j -> JSONUtil.toBean(j, AgentChatMessage.class))
                .collect(Collectors.toList());
    }

    /** 追加一条消息:RPUSH JSON → LTRIM 保留最近 N 条 → 滑动续期。 */
    public void append(String sessionId, String role, String content) {
        String key = RedisConstants.AGENT_SESSION_KEY + sessionId + MSGS_KEY_SUFFIX;
        stringRedisTemplate.opsForList().rightPush(key, JSONUtil.toJsonStr(new AgentChatMessage(role, content)));
        stringRedisTemplate.opsForList().trim(key, -RedisConstants.AGENT_SESSION_MAX_MSGS, -1);
        stringRedisTemplate.expire(key, RedisConstants.AGENT_SESSION_TTL, TimeUnit.HOURS);
    }

    /** 读取会话归属用户 id(未绑定返回 null)。 */
    public String loadOwner(String sessionId) {
        return (String) stringRedisTemplate.opsForHash().get(metaKey(sessionId), FIELD_OWNER);
    }

    /** 绑定会话归属用户,并续期 meta。 */
    public void bindOwner(String sessionId, Long userId) {
        stringRedisTemplate.opsForHash().put(metaKey(sessionId), FIELD_OWNER, String.valueOf(userId));
        stringRedisTemplate.expire(metaKey(sessionId), RedisConstants.AGENT_SESSION_TTL, TimeUnit.HOURS);
    }

    /** 读取上次推荐意图的原始 JSON 字符串,直接喂 prompt 供指代消解。 */
    public String loadLastIntent(String sessionId) {
        return (String) stringRedisTemplate.opsForHash().get(metaKey(sessionId), FIELD_LAST_INTENT);
    }

    /** 覆盖保存上次推荐意图,并续期 meta。 */
    public void saveLastIntent(String sessionId, Map<String, Object> intent) {
        stringRedisTemplate.opsForHash().put(metaKey(sessionId), FIELD_LAST_INTENT, JSONUtil.toJsonStr(intent));
        stringRedisTemplate.expire(metaKey(sessionId), RedisConstants.AGENT_SESSION_TTL, TimeUnit.HOURS);
    }

    private String metaKey(String sessionId) {
        return RedisConstants.AGENT_SESSION_KEY + sessionId + META_KEY_SUFFIX;
    }
}
