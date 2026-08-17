from langchain_openai import ChatOpenAI

from . import config


def get_llm() -> ChatOpenAI:
    """构造 DeepSeek 客户端(OpenAI 兼容协议)。"""
    return ChatOpenAI(
        model=config.DEEPSEEK_MODEL,
        api_key=config.DEEPSEEK_API_KEY,
        base_url=config.DEEPSEEK_BASE_URL,
        temperature=0.7,
    )
