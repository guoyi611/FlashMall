import json
import time
import uuid
from typing import Any, Dict, Generator, List

from langchain_core.messages import HumanMessage, SystemMessage

from . import shop_client
from .llm import get_llm
from .sse import sse

INTENT_AGENT = {"agentId": 1, "name": "意图识别Agent", "icon": "cpu"}
SEARCH_AGENT = {"agentId": 2, "name": "搜索Agent", "icon": "search"}
ANALYZE_AGENT = {"agentId": 3, "name": "分析Agent", "icon": "data-line"}
RECOMMEND_AGENT = {"agentId": 4, "name": "推荐Agent", "icon": "medal-1"}
SUMMARIZE_AGENT = {"agentId": 5, "name": "汇总Agent", "icon": "document-copy"}

# 最终推荐的商户数量
TOP_N = 3

# 防幻觉约束:要求 LLM 只用真实商户列表,不编造
_NO_HALLUCINATION = (
    "【严格约束】只能使用「Top 商户」列表中的商户，严禁编造或推荐列表之外的商户；"
    "每家商户的 id、名称、评分、人均必须与列表完全一致。"
)


def run_pipeline(req) -> Generator[str, None, None]:
    """编排:意图识别(LLM) -> 搜索(HTTP) -> 分析(代码排序) -> 推荐(代码取TopN) -> 汇总(LLM文案)。"""
    if not req.message:
        yield sse("error", {"code": "AGENT_BAD_REQUEST", "message": "消息不能为空"})
        return

    session_id = req.sessionId or f"s_{uuid.uuid4().hex}"
    yield sse("session", {"sessionId": session_id})

    llm = get_llm()
    try:
        # 1. 意图识别(LLM)
        yield sse("agent", _agent_data(INTENT_AGENT, "running", description="解析用户意图..."))
        start = time.time()
        intent = _recognize_intent(llm, req.message)
        yield sse("agent", _agent_data(INTENT_AGENT, "done", duration=_elapsed(start),
                                       description=_intent_desc(intent)))

        # 2. 搜索(HTTP 调 Java 商城接口,多页)
        yield sse("agent", _agent_data(SEARCH_AGENT, "running", description="检索候选商户..."))
        start = time.time()
        shops = _search_shops(intent)
        yield sse("agent", _agent_data(SEARCH_AGENT, "done", duration=_elapsed(start),
                                       description=f"检索到 {len(shops)} 家候选商户"))

        if not shops:
            yield sse("message", {"content": "抱歉，没有找到符合条件的商户。", "sources": []})
            yield sse("done", {})
            return

        # 3. 分析(代码:预算过滤 + 评分/销量排序)
        yield sse("agent", _agent_data(ANALYZE_AGENT, "running", description="按评分/预算筛选排序..."))
        start = time.time()
        ranked = _rank_shops(shops, intent.get("budget"))
        yield sse("agent", _agent_data(ANALYZE_AGENT, "done", duration=_elapsed(start),
                                       description=f"筛选后 {len(ranked)} 家"))

        if not ranked:
            yield sse("message", {"content": "预算范围内没有找到合适的商户。", "sources": []})
            yield sse("done", {})
            return

        # 4. 推荐(代码:取 TopN)
        yield sse("agent", _agent_data(RECOMMEND_AGENT, "running", description="生成 TopN 推荐..."))
        start = time.time()
        top = _pick_top(ranked, TOP_N)
        yield sse("agent", _agent_data(RECOMMEND_AGENT, "done", duration=_elapsed(start),
                                       description=f"推荐 Top{len(top)}"))

        # 5. 汇总(LLM:基于 Top 商户生成文案)
        yield sse("agent", _agent_data(SUMMARIZE_AGENT, "running", description="生成 markdown 文案..."))
        start = time.time()
        markdown = _summarize(llm, req.message, top)
        yield sse("agent", _agent_data(SUMMARIZE_AGENT, "done", duration=_elapsed(start),
                                       description="已生成回复"))

        yield sse("message", {"content": markdown, "sources": _build_sources(top)})
        yield sse("done", {})
    except Exception as e:  # noqa: BLE001
        yield sse("error", {"code": "AGENT_INTERNAL_ERROR", "message": str(e)})


def _recognize_intent(llm, message: str) -> Dict[str, Any]:
    """LLM 解析意图:自然语言 -> {typeId, typeName, budget, keyword}。"""
    types = shop_client.list_shop_types()
    type_lines = "\n".join(f"{t.get('id')} {t.get('name')}" for t in types)
    system = (
        "你是意图识别Agent。根据用户需求，从下面的商户类型列表里选择最匹配的一个类型，"
        "并提取预算等约束。只输出 JSON，不要输出其他内容。\n"
        f"商户类型列表:\n{type_lines}\n"
        'JSON 格式:{"typeId": 整数, "typeName": "名称", "budget": 整数或null, "keyword": 字符串或null}\n'
        '注意:用户没有明确提到预算时，budget 必须为 null，严禁臆测数值。'
    )
    raw = _llm(llm, system, f"用户需求:{message}")
    intent = _parse_json(raw)
    return {
        "typeId": _to_int(intent.get("typeId")),
        "typeName": intent.get("typeName"),
        "budget": _to_int(intent.get("budget")),
        "keyword": intent.get("keyword"),
    }


def _search_shops(intent: Dict[str, Any], pages: int = 3) -> List[Dict[str, Any]]:
    """代码检索:按类型翻页取商户,凑够候选。"""
    type_id = intent.get("typeId")
    if not (isinstance(type_id, int) and type_id > 0):
        keyword = intent.get("keyword")
        return shop_client.query_shops_by_name(keyword) if keyword else []
    shops: List[Dict[str, Any]] = []
    for page in range(1, pages + 1):
        batch = shop_client.query_shops_by_type(type_id, page)
        if not batch:
            break
        shops.extend(batch)
    return shops


def _rank_shops(shops: List[Dict[str, Any]], budget) -> List[Dict[str, Any]]:
    """代码排序:先按预算过滤,再按评分降序、销量降序。"""
    if isinstance(budget, int) and budget > 0:
        shops = [s for s in shops if (s.get("avgPrice") or 0) <= budget]
    return sorted(shops, key=lambda s: ((s.get("score") or 0), (s.get("sold") or 0)), reverse=True)


def _pick_top(ranked: List[Dict[str, Any]], n: int) -> List[Dict[str, Any]]:
    return ranked[:n]


def _summarize(llm, message: str, top: List[Dict[str, Any]]) -> str:
    system = (
        "你是汇总Agent。根据用户需求和已筛选出的 Top 商户，生成给用户看的 markdown 推荐文案："
        "每家商户一行，包含名称、评分(满分5)、人均(元)和一句推荐理由，"
        "并用链接 [查看详情](/shop-detail.html?id=商户id) 结尾。"
        + _NO_HALLUCINATION
    )
    user = f"用户需求:{message}\nTop 商户:\n{_shops_to_text(top)}"
    return _llm(llm, system, user)


def _llm(llm, system: str, user: str) -> str:
    resp = llm.invoke([SystemMessage(content=system), HumanMessage(content=user)])
    return (resp.content or "").strip()


def _shops_to_text(shops) -> str:
    lines = []
    for s in shops:
        score = (s.get("score") or 0) / 10.0
        lines.append(
            f"- id={s.get('id')} {s.get('name')} 评分{score:.1f} 人均{s.get('avgPrice')}元 "
            f"销量{s.get('sold')} 商圈{s.get('area')}"
        )
    return "\n".join(lines)


def _build_sources(shops, top_n: int = 3):
    return [
        {"name": s.get("name"), "url": f"/shop-detail.html?id={s.get('id')}"}
        for s in shops[:top_n]
    ]


def _intent_desc(intent) -> str:
    type_name = intent.get("typeName") or "未知"
    budget = intent.get("budget")
    return f"识别意图:{type_name} | {'人均≤' + str(budget) if budget else '不限预算'}"


def _clip(text: str, n: int = 60) -> str:
    return (text or "").strip().replace("\n", " ")[:n]


def _elapsed(start: float) -> int:
    return int((time.time() - start) * 1000)


def _to_int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def _parse_json(text) -> Dict[str, Any]:
    text = (text or "").strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.startswith("json"):
            text = text[4:]
        text = text.strip()
    try:
        result = json.loads(text)
        return result if isinstance(result, dict) else {}
    except Exception:
        return {}


def _agent_data(agent, status, description=None, duration=None, output=None):
    data = {"agentId": agent["agentId"], "name": agent["name"], "icon": agent["icon"], "status": status}
    if description is not None:
        data["description"] = description
    if duration is not None:
        data["duration"] = duration
    if output is not None:
        data["output"] = output
    return data
