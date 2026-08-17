from typing import Optional

from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from .agents import run_pipeline

app = FastAPI(title="AI Mall Agent")


class ChatRequest(BaseModel):
    message: str
    sessionId: Optional[str] = None
    userId: Optional[int] = None


@app.post("/chat")
def chat(req: ChatRequest):
    """SSE 流式对话,事件流格式与前端约定一致(session/agent/message/done/error)。"""
    return StreamingResponse(run_pipeline(req), media_type="text/event-stream")
