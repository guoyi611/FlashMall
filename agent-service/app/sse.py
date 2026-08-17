import json


def sse(event: str, data) -> str:
    """构造一个 SSE 事件块(以空行结尾),与前端约定一致。"""
    payload = json.dumps(data, ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n"
