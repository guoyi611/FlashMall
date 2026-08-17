import os

from dotenv import load_dotenv

load_dotenv()

# DeepSeek(OpenAI 兼容)
DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "sk-xxx-placeholder")
DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")

# Java 后端(取商城数据,下一轮接入)
JAVA_BASE_URL = os.getenv("JAVA_BASE_URL", "http://127.0.0.1:8081")
