# 智能体多轮会话 + Redis 会话隔离 — 实现方案

> 状态：方案定稿，可供参照自行实现
> 关联：`AGENT_API.md`（SSE 事件协议，本方案不改动协议）

## 1. 背景与目标

当前 `AgentPipeline` 是**无状态单轮**推荐管道：`sessionId` 只在生成后经 SSE 回传给前端，从不落存储；`DeepSeekClient` 每次只发 `system + user` 两条消息。因此第二轮问"我第一次吃的是哪家店"时意图识别为空、搜索无结果，命中兜底文案"抱歉，没有找到符合条件的商户。"

目标：

1. **完整多轮**——追问召回（"第一次是哪家"）+ 指代重新推荐（"再便宜一点"基于上文调整预算再搜）。
2. 会话历史改用 **Redis** 存储，天然支持多服务器部署，不再依赖单机 session 容器。
3. 会话**绑定登录用户**（`meta.owner`），匿名用户各自独立会话。

## 2. 技术栈选型

| 项 | 选型 | 理由 |
|---|---|---|
| 会话存储 | Redis（`StringRedisTemplate` + hutool `JSONUtil`） | 复用全项目唯一 Redis 客户端与登录 token 模式（`login:token:` Hash + 滑动续期）；天然分布式 |
| 序列化 | hutool `JSONUtil`（String 存 JSON） | 与 `CacheClient` 一致，零新增依赖 |
| 多轮路由 | 一次 LLM JSON-mode 调用，输出 `action` | 比规则匹配鲁棒，支持中文指代消解 |
| LLM 客户端 | 现有 `DeepSeekClient` 加历史重载 | 不引入新依赖 |

## 3. Redis 存储设计

每个会话两类 key：

```
agent:session:<id>:messages   List  — 对话历史（每条为 AgentChatMessage JSON），RPUSH + LTRIM 截断
agent:session:<id>:meta       Hash  — owner(归属用户id) / lastIntent(上次推荐意图JSON)
```

- `messages`：滑动续期 24h，截断保留最近 20 条（`AGENT_SESSION_MAX_MSGS`）
- `meta.owner`：会话一旦被登录用户绑定，只允许本人继续，防止凭 sessionId 串读他人历史
- `meta.lastIntent`：存上次推荐的结构化意图，供"再便宜一点"这类指代在 prompt 中解析预算调整

key 命名延续 `RedisConstants` 的 `领域:实体:字段` 冒号分隔小写约定。

## 4. 逐文件实现

### 4.1 `utils/RedisConstants.java` 追加

```java
public static final String AGENT_SESSION_KEY = "agent:session:";
public static final Long AGENT_SESSION_TTL = 24L;        // 小时
public static final int AGENT_SESSION_MAX_MSGS = 20;
```

### 4.2 新增 `dto/agent/AgentChatMessage.java`

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentChatMessage {
    /** 消息角色:user / assistant(与 OpenAI 协议一致) */
    private String role;
    private String content;
}
```

### 4.3 新增 `agent/store/AgentSessionStore.java`

`@Component`，注入 `StringRedisTemplate`，**所有方法 `public`**（供跨包 `AgentPipeline` 调用），方法：

- `List<AgentChatMessage> loadMessages(String sessionId)` → `LRANGE key 0 -1`，逐条 `JSONUtil.toBean`
- `void append(String sessionId, String role, String content)` → `RPUSH` JSON → `LTRIM key -20 -1` → `expire 24h`
- `String loadOwner(String sessionId)` / `void bindOwner(String sessionId, Long userId)` → Hash 读写 `owner`，owner 落库统一存 `String.valueOf(userId)`（避免后续 Long/String 比较类型不匹配恒拒）
- `String loadLastIntent(String sessionId)` / `void saveLastIntent(String sessionId, Map<String,Object> intent)` → Hash 读写 `lastIntent`（存 `JSONUtil.toJsonStr(intent)`，读出来是原始 JSON 字符串直接喂 prompt）

key 拼接（`AGENT_SESSION_KEY` 末尾已带冒号，`messages`/`meta` 前需再补冒号）：
`AGENT_SESSION_KEY + sessionId + ":messages"`、`AGENT_SESSION_KEY + sessionId + ":meta"`。

### 4.4 `agent/client/DeepSeekClient.java` 加历史重载

```java
public String chat(String system, List<AgentChatMessage> history, String user) {
    return chat(system, history, user, temperature, false);
}
public String chatJson(String system, List<AgentChatMessage> history, String user) {
    return chat(system, history, user, temperature, true);
}
// 私有核心: messages 数组 = system + history + 末尾追加 user
```

保留原有 `chat(system, user)` / `chatJson(system, user)` 委托旧逻辑。

**坑① HttpURLConnection keep-alive**：第二个请求复用连接池里被服务端关闭的旧连接，报 `SocketException: Unexpected end of file from server`。解决：`conn.setRequestProperty("Connection", "close")` 每次新建连接；连接层错误/5xx 重试 1 次，4xx 直接抛。若网络环境走代理且 `Connection: close` 有副作用，去掉并接受偶发重试（每次 `conn.disconnect()`）。
**坑② `readAll(null)`**：4xx 且 `getErrorStream()` 为 null 时 NPE 会绕过重试逻辑，方法开头加 `if (in == null) return "";`。

### 4.5 `agent/prompt/Prompts.java` 新增两个 prompt

- `understandSystem(List<ShopType> types, String lastIntentCtx)`：路由+意图合并成**一次 LLM 调用**，输出
  `{"action":"recommend"|"chat","typeId":..,"typeName":..,"budget":..,"keyword":..}`。
  将 `lastIntentCtx`（上次意图 JSON）注入，并指示："用户在调整上次条件时，budget/typeId 必须以上次意图为基础增减，不得臆测"；用户未提预算时 budget 必须为 null。
- `chatSystem()`：追问回答，只依据历史出现过的商户，不搜新商户，复用 `NO_HALLUCINATION`。

**坑③ Java 8 无 `String.isBlank()`**，用 hutool `StrUtil.isBlank()`。
**坑④ prompt 字符串内中文引号必须用全角 `“”`**，ASCII `"` 会把 Java 字符串提前终止（编译报"需要';'"）。

> 注：原有 `intentSystem` 已被 `understandSystem` 完全取代，删除不再使用。

### 4.6 `agent/AgentPipeline.java` 核心改造

`run(AgentChatRequest req, Long userId, SseEmitter emitter)`：

1. 生成/沿用 sessionId（保持原逻辑），推 `session` 事件
2. **owner 校验**：`loadOwner` 非空且 ≠ userId → 推 `error`(AGENT_SESSION_FORBIDDEN) 返回；owner 空且 userId 非空 → `bindOwner`；都空（匿名）→ 放行。比较统一走 `!owner.equals(userId == null ? null : String.valueOf(userId))`（owner 是 String，userId 是 Long，直接 equals 恒为 false）
3. `history = loadMessages(sessionId)`；`lastIntent = loadLastIntent(sessionId)`（**放入 try 内**，Redis 故障不至于打穿线程池）
4. **步骤1 = 一次 LLM 路由**：`understand(history, lastIntent, message)`，返回含 `action` 的 intent Map
5. **action=chat 分支**：`llm.chat(chatSystem(), history, message)` 生成 markdown；SSE 上步骤 2/3/4/5 全发 DONE"跳过"（保持前端 5 卡不悬挂，仅跳 2/3/4 会漏掉第 5 卡），sources 发空列表；落库 user+assistant
6. **action=recommend**：走原 5 步（搜索→分析→推荐→汇总，intent 已含 typeId/budget/keyword）；落库 user+assistant，`saveLastIntent(intent)`
7. **兜底分支也落库**：`shops.isEmpty()` / `ranked.isEmpty()` 两个提前 return 的分支同样落库 user + 兜底文案（否则"问了个无结果的问题"后，下一轮指代没有上下文）

`understand` 保留原 `recognizeIntent` 的 **typeId 数据库校验**（防 LLM 幻觉），校验失败降级走关键词搜索。

SSE 事件名 `session/agent/message/done/error` 全部保持不变，前端无需改动。

### 4.7 `service/impl/AgentServiceImpl.java`

**坑⑤ ThreadLocal 跨线程失效（关键）**：`UserHolder` 写在 servlet 线程，pipeline 跑在固定线程池，跨线程拿不到。必须在 `chat()` 提交线程池**前**解析：

```java
Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
executor.execute(() -> {
    try {
        pipeline.run(request, userId, emitter);
    } finally {
        emitter.complete();
    }
});
```

## 5. 验证方式

1. 连续两次 `POST /api/agent/chat`（同 `sessionId`）：先推荐、再问"上次吃的是哪家"→ 返回店名而非兜底文案
2. "再便宜一点" → 基于上次预算下调后重新推荐
3. `redis-cli` 查看 `agent:session:<id>:messages`（≤20 条、TTL 24h）与 `:meta`（owner/lastIntent）
4. 带 token 对话后，换另一 token 或同 sessionId 无 token 请求 → 被拒（AGENT_SESSION_FORBIDDEN）
5. 匿名（无 token）连续对话 → 各自独立会话，多轮正常

## 6. 先排除网络/配置问题

首次调用就走 `understand` 直连 DeepSeek，失败通常与多轮逻辑无关。先 curl 验证：

```bash
curl -s https://api.deepseek.com/chat/completions \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"hi"}]}'
```

## 7. 可选后续（本次未做）

- AGENT_API.md 预留的会话历史接口：`GET /agent/session/list`、`GET /agent/session/{id}/messages`、`DELETE /agent/session/{id}`
- 清理已提交未使用的 `agent-service/` Python 残留（删除前需确认）
