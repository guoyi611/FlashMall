import httpx

from . import config


def _get(path: str, params: dict = None):
    """调 Java 后端接口,返回 Result.data(失败返回 None)。"""
    url = f"{config.JAVA_BASE_URL}{path}"
    resp = httpx.get(url, params=params, timeout=10.0)
    resp.raise_for_status()
    body = resp.json()
    if not body.get("success"):
        return None
    return body.get("data")


def list_shop_types():
    """GET /shop-type/list -> 商户类型列表 [{id, name, icon, sort}]"""
    return _get("/shop-type/list") or []


def query_shops_by_type(type_id, current: int = 1):
    """GET /shop/of/type -> 某类型下的商户列表"""
    return _get("/shop/of/type", {"typeId": type_id, "current": current}) or []


def query_shops_by_name(name: str, current: int = 1):
    """GET /shop/of/name -> 按名称搜索商户"""
    return _get("/shop/of/name", {"name": name, "current": current}) or []
