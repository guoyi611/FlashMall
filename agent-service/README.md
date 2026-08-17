# AI Mall Agent(Python 服务)

多 Agent 协作的 AI 商城管家,提供 SSE 流式对话。由 Java 网关(`/agent/chat`)验 token 后转发调用。

## 运行

```bash
cd agent-service
pip install -r requirements.txt
cp .env.example .env        # 编辑 .env,填入真实 DEEPSEEK_API_KEY
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## 接口

`POST /chat` — 请求体 `{message, sessionId?, userId?}`,返回 `text/event-stream`。

事件流:`session` → `agent`(running/done 成对,共 5 个)→ `message` → `done`,出错发 `error`。

## 说明

- LLM 用 DeepSeek(OpenAI 兼容),通过 `langchain-openai` 的 `ChatOpenAI` 接入。
- 5 个 Agent(意图识别/搜索/分析/推荐/汇总)当前为顺序 LLM 编排。
- 搜索 Agent 接入 Java 商城真实数据(`/shop/of/type`、`/shop/of/name`)为下一步 TODO。
