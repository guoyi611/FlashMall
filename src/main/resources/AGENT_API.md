# AI 商城管家 — 前端对接后端 API 文档

> 适用项目:黑马点评商城(nginx + SpringBoot)
> 本文档分两部分:**通用约定** → **AI 管家接口**(后端需新增)。

---

## 一、通用约定

### 1. 基础路径

- 前端请求统一走 `/api` 前缀,由 nginx 反向代理到后端 `127.0.0.1:8081`([nginx.conf:31](conf/nginx.conf#L31))
- 前端已统一配置在 [html/hmdp/js/common.js:2](html/hmdp/js/common.js#L2):`axios.defaults.baseURL = "/api"`

### 2. 认证方式

- 登录成功后后端返回 token 字符串,前端存入 `sessionStorage["token"]`([login.html:84](html/hmdp/login.html#L84))
- 之后所有请求通过请求头携带:`Authorization: <token>`([common.js:10](html/hmdp/js/common.js#L10))
- 返回 HTTP 401 → 前端自动跳转 `/login.html`

### 3. 响应包装格式

所有普通接口返回统一结构:

```json
{
  "success": true,
  "data": { },
  "errorMsg": ""
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否成功 |
| data | any | 业务数据,失败时为 null |
| errorMsg | string | 失败时的错误信息(前端直接展示给用户) |

前端拦截器行为([common.js:18-35](html/hmdp/js/common.js#L18-L35)):
- `success === false` → reject(errorMsg),前端 `$message.error` 提示
- HTTP 401 → 跳转登录页
- 网络/服务端异常 → reject("服务器异常")

### 4. 通用参数

- 分页参数: `current`(页码,从 1 开始)
- 时间字段使用时间戳(ms)或 ISO 字符串

---

## 二、现有商城接口(前端已对接,后端已存在)

### 用户模块

| 方法 | 路径 | 说明 | 请求 | 响应 data |
|------|------|------|------|-----------|
| POST | `/user/login` | 手机号+验证码登录 | `{phone, code}` | token(字符串) |
| POST | `/user/code?phone=` | 发送验证码 | query | - |
| GET | `/user/me` | 当前登录用户 | header | 用户对象 |
| GET | `/user/{id}` | 用户信息 | path | 用户对象 |
| GET | `/user/info/{userId}` | 用户详情 | path | 用户详情 |
| POST | `/user/logout` | 退出登录 | - | - |

### 商户模块

| 方法 | 路径 | 说明 | 请求 | 响应 data |
|------|------|------|------|-----------|
| GET | `/shop-type/list` | 商户类型列表 | - | 类型数组 |
| GET | `/shop/of/type` | 按类型分页查商户 | `typeId, current, sortBy, x, y` | 商户数组(字段含 images 逗号分隔) |
| GET | `/shop/of/name?name=` | 按名称搜索 | query | 商户数组 |
| GET | `/shop/{id}` | 商户详情 | path | 商户对象(images 逗号分隔) |

### 代金券 / 秒杀

| 方法 | 路径 | 说明 | 请求 | 响应 data |
|------|------|------|------|-----------|
| GET | `/voucher/list/{shopId}` | 商户代金券列表 | path | 券数组 |
| POST | `/voucher-order/seckill/{id}` | 秒杀抢券 | path | 订单 id |

### 博客 / 笔记

| 方法 | 路径 | 说明 | 请求 | 响应 data |
|------|------|------|------|-----------|
| GET | `/blog/hot?current=` | 热门笔记分页 | query | 笔记数组 |
| GET | `/blog/{id}` | 笔记详情 | path | 笔记对象 |
| GET | `/blog/of/me` | 我的笔记 | - | 笔记数组 |
| GET | `/blog/of/follow` | 关注人的笔记 | `offset, lastId` | `{list, offset, lastId}` |
| GET | `/blog/of/user?userId=&current=` | 某人的笔记 | query | 笔记数组 |
| GET | `/blog/likes/{id}` | 笔记点赞用户列表 | path | 用户数组 |
| PUT | `/blog/like/{id}` | 点赞/取消点赞 | path | - |
| POST | `/blog` | 发布笔记 | `{title, images, content}` | 笔记 id |
| GET | `/upload/blog/delete?name=` | 删除已上传图片 | query | - |

### 关注

| 方法 | 路径 | 说明 | 请求 | 响应 data |
|------|------|------|------|-----------|
| GET | `/follow/or/not/{userId}` | 是否已关注 | path | boolean |
| PUT | `/follow/{userId}/{followed}` | 关注/取关 | path | - |
| GET | `/follow/common/{userId}` | 共同关注 | path | 用户数组 |

---

## 三、AI 管家接口(后端需新增)

> 核心:多 Agent 协作。用户发一条消息,后端编排多个 Agent(意图识别→搜索→分析→推荐→汇总),**实时推送各 Agent 状态**,最终返回结果。
> 推荐使用 **SSE 流式**方案,前端能实时看到 Agent 协作过程(与现有 [agent.html](html/hmdp/agent.html) 的 mock 效果一致)。

### 方案一(推荐):SSE 流式聊天

#### `POST /api/agent/chat`

**请求头**

```
Content-Type: application/json
Authorization: <token>      // 可空,匿名也可对话
```

**请求体**

```json
{
  "message": "帮我推荐周末聚餐的餐厅，人均200以内",
  "sessionId": "可选，不传则服务端新建会话"
}
```

**响应**:HTTP 200,`Content-Type: text/event-stream`,SSE 事件流。

**事件格式**

| 事件名 | data 内容 | 说明 |
|--------|-----------|------|
| `session` | `{"sessionId":"xxx"}` | 会话建立,返回会话 id |
| `agent` | 见下方 | Agent 状态更新(每个 Agent 发 running/done 两条) |
| `message` | `{"content":"markdown 文本","sources":[...]}` | 最终回复(markdown 格式) |
| `done` | `{}` | 本轮结束 |
| `error` | `{"code":"AGENT_xxx","message":"错误信息"}` | 出错 |

**agent 事件 data 字段**

```json
{
  "agentId": 1,
  "name": "意图识别Agent",
  "icon": "cpu",                // 前端图标 key,可省略由前端映射
  "status": "running",           // pending | running | done | error
  "description": "分析用户意图...",  // 当前执行说明/结果摘要
  "duration": 612,               // 执行耗时(ms),仅 done 时有效
  "output": "可选，Agent 产出"
}
```

**完整事件流示例**(对应用户发一条消息)

```
event: session
data: {"sessionId":"s_1001"}

event: agent
data: {"agentId":1,"name":"意图识别Agent","status":"running","description":"分析用户意图..."}
event: agent
data: {"agentId":1,"name":"意图识别Agent","status":"done","duration":612,"description":"识别意图：聚餐推荐 | 人均<200 | 周末"}

event: agent
data: {"agentId":2,"name":"搜索Agent","status":"running","description":"检索候选商户..."}
event: agent
data: {"agentId":2,"name":"搜索Agent","status":"done","duration":1203,"description":"检索到 28 家候选商户"}

... (分析Agent / 推荐Agent / 汇总Agent 同理)

event: message
data: {"content":"**为您找到以下 5 家优质聚餐餐厅：**\n\n1. **海底捞火锅** ⭐4.8 | 人均￥150\n   > 服务好，适合多人聚餐\n   [查看详情](/shop-detail.html?id=1)\n\n...","sources":[{"name":"海底捞火锅","url":"/shop-detail.html?id=1"}]}

event: done
data: {}
```

**前端接入示例**(agent.html 中替换 mock,用原生 fetch 解析 SSE,无需 axios)

```js
async function chat(message, handlers) {
  const res = await fetch('/api/agent/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': sessionStorage.getItem('token') || ''
    },
    body: JSON.stringify({ message })
  });
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const blocks = buffer.split('\n\n');   // SSE 事件以空行分隔
    buffer = blocks.pop();
    for (const block of blocks) {
      const lines = block.split('\n');
      const event = (lines.find(l => l.startsWith('event:')) || '').replace('event:', '').trim();
      const dataStr = lines.filter(l => l.startsWith('data:'))
                           .map(l => l.replace('data:', '').trim()).join('\n');
      if (!event || !dataStr) continue;
      const data = JSON.parse(dataStr);
      if (event === 'agent' && handlers.onAgent) handlers.onAgent(data);
      else if (event === 'message' && handlers.onMessage) handlers.onMessage(data.content);
      else if (event === 'error' && handlers.onError) handlers.onError(data.message);
      else if (event === 'done' && handlers.onDone) handlers.onDone();
    }
  }
}

// 用法
chat('帮我推荐周末聚餐的餐厅', {
  onAgent: (a) => updateAgentCard(a),     // 更新工作台卡片状态
  onMessage: (content) => pushAssistantMsg(content),
  onError: (msg) => this.$message.error(msg),
  onDone: () => { this.agentsRunning = false; }
});
```

### 方案二(备选):异步任务 + 轮询

后端不想做 SSE 时使用,前端通过轮询获取进度。

#### 1. `POST /api/agent/task` — 提交任务

**请求体**: `{"message":"...", "sessionId":"可选"}`

**响应 data**:
```json
{ "taskId": "t_1001", "sessionId": "s_1001" }
```

#### 2. `GET /api/agent/task/{taskId}` — 查询任务状态

**响应 data**:
```json
{
  "status": "RUNNING",        // RUNNING | SUCCESS | FAILED
  "agents": [
    { "agentId": 1, "name": "意图识别Agent", "status": "done", "duration": 612, "description": "..." },
    { "agentId": 2, "name": "搜索Agent", "status": "running", "duration": 0, "description": "..." }
  ],
  "result": "最终回复(markdown),status=SUCCESS 时才有",
  "errorMsg": "失败原因,status=FAILED 时才有"
}
```

**前端轮询逻辑**:提交后每 800ms 调用一次 `GET /api/agent/task/{taskId}`,用 `agents` 数组驱动工作台卡片,直到 `status` 变为 `SUCCESS` 或 `FAILED`。

### 会话历史接口(可选,第二优先级)

| 方法 | 路径 | 说明 | 响应 data |
|------|------|------|-----------|
| GET | `/api/agent/session/list` | 我的会话列表 | 会话数组(含摘要、时间) |
| GET | `/api/agent/session/{sessionId}/messages` | 会话消息历史 | `[{role:'user'\|'assistant', content, time}]` |
| DELETE | `/api/agent/session/{sessionId}` | 删除会话 | - |

---

## 四、前端改动点清单(对接真实后端)

| 位置 | 改动 |
|------|------|
| [agent.html](html/hmdp/agent.html) | 将 `AGENT_PIPELINE` + `MOCK_RESPONSES` 的 mock 逻辑替换为上述 fetch/SSE 调用;`stepAgent` 的 setTimeout 模拟改为真实 `agent` 事件驱动 |
| [agent.html](html/hmdp/agent.html) | 空状态欢迎页、hints 快捷提问保留;`messages` 结构兼容 `role`/`content`/`time` |
| [common.js](html/hmdp/js/common.js) | 无需改动(SSE 用原生 fetch,不经过 axios 拦截器;若走方案二轮询,axios 拦截器已兼容) |
| nginx.conf | 无需改动(`/api` 已代理) |

---

## 五、Agent 定义(后端实现参考)

| agentId | 名称 | 职责 | 建议 icon |
|---------|------|------|-----------|
| 1 | 意图识别Agent | 解析用户意图与约束条件 | cpu |
| 2 | 搜索Agent | 检索候选商户/商品(调用现有 `/shop/of/type`、`/shop/of/name` 等) | search |
| 3 | 分析Agent | 按评分/人均/距离/营业时间排序筛选 | data-line |
| 4 | 推荐Agent | 综合排序产出 TopN 推荐理由 | medal-1 |
| 5 | 汇总Agent | 生成 markdown 文案与 sources 链接 | document-copy |

> 后端 Agent 可复用现有商城接口(`/shop/of/type`、`/shop/of/name`、`/voucher/list/{shopId}` 等)作为工具,由编排器按意图调度。
