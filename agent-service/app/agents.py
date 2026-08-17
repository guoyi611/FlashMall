import time
import uuid
from typing import Any, Dict, Generator

from langchain_core.messages import HumanMessage, SystemMessage

from .llm import get_llm
from .sse import sse

# 5 个 Agent 定义(agentId / name / icon 与前端约定一致)
AGENTS = [
    {"agentId": 1, "name": "意图识别Agent", "icon": "cpu",
     "role": "解析用户意图与约束条件(品类、预算、人数、时间等),输出结构化摘要"},
    {"agentId": 2, "name": "搜索Agent", "icon": "search",
     "role": "根据意图确定候选商户的检索策略(类型/关键词/筛选条件)"},
    {"agentId": 3, "name": "分析Agent", "icon": "data-line",
     "role": "按评分、人均、距离等维度对候选商户排序筛选"},
    {"agentId": 4, "name": "推荐Agent", "icon": "medal-1",
     "role": "综合排序产出 TopN 推荐清单及推荐理由"},
    {"agentId": 5, "name": "汇总Agent", "icon": "document-copy",
     "role": "生成最终 markdown 文案与推荐来源链接"},
]


def run_pipeline(req) -> Generator[str, None, None]:
    """按顺序编排各 Agent,以 SSE 事件流产出结果。"""
    if not req.message:
        yield sse("error", {"code": "AGENT_BAD_REQUEST", "message": "消息不能为空"})
        return

    session_id = req.sessionId or f"s_{uuid.uuid4().hex}"
    yield sse("session", {"sessionId": session_id})

    llm = get_llm()
    context: Dict[str, Any] = {"用户输入": req.message, "userId": req.userId}

    try:
        for agent in AGENTS:
            yield sse("agent", _agent_data(agent, "running", description=agent["role"]))
            start = time.time()
            # TODO 下一轮:搜索Agent 改为调用 Java 商城 REST(/shop/of/type、/shop/of/name)
            output = _invoke(llm, agent, context)
            duration = int((time.time() - start) * 1000)
            context[agent["name"]] = output
            yield sse("agent", _agent_data(agent, "done", duration=duration,
                                           description=(output or "").strip()[:60]))

        markdown = context.get("汇总Agent", "")
        yield sse("message", {"content": markdown, "sources": []})
        yield sse("done", {})
    except Exception as e:  # noqa: BLE001
        yield sse("error", {"code": "AGENT_INTERNAL_ERROR", "message": str(e)})


def _invoke(llm, agent: Dict[str, Any], context: Dict[str, Any]) -> str:
    system = f"你是电商平台的{agent['name']}。你的职责:{agent['role']}。请用中文、简洁地输出结果。"
    user = _build_context(context)
    resp = llm.invoke([SystemMessage(content=system), HumanMessage(content=user)])
    return (resp.content or "").strip()


def _build_context(context: Dict[str, Any]) -> str:
    parts = [f"{k}:\n{v}" for k, v in context.items() if v is not None]
    return "\n\n".join(parts)


def _agent_data(agent: Dict[str, Any], status: str,
                description: str = None, duration: int = None, output: str = None) -> Dict[str, Any]:
    """只输出前端契约字段(agentId/name/icon/status/description/duration/output)。"""
    data = {
        "agentId": agent["agentId"],
        "name": agent["name"],
        "icon": agent["icon"],
        "status": status,
    }
    if description is not None:
        data["description"] = description
    if duration is not None:
        data["duration"] = duration
    if output is not None:
        data["output"] = output
    return data
