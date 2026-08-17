package com.hmdp.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.agent.ChatMessage;
import com.hmdp.dto.agent.ChatRequest;
import com.hmdp.dto.agent.SessionInfo;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IAgentService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.DeepSeekClient;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * AI 智能管家实现：SSE 编排 5 个 Agent，DeepSeek 负责意图识别与文案生成，
 * 检索/排序复用现有商城服务，会话历史持久化到 Redis。
 */
@Slf4j
@Service
public class AgentServiceImpl implements IAgentService {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(8);

    private static final String[] AGENT_NAMES = {
            "意图识别Agent", "搜索Agent", "分析Agent", "推荐Agent", "汇总Agent"
    };
    private static final String[] AGENT_ICONS = {
            "el-icon-cpu", "el-icon-search", "el-icon-data-line", "el-icon-medal-1", "el-icon-document-copy"
    };

    /** Redis key 前缀 */
    private static final String SESSION_KEY = "agent:session:";
    private static final String USER_SESSIONS_KEY = "agent:user:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private DeepSeekClient deepSeekClient;
    @Resource
    private AiProperties aiProperties;
    @Resource
    private IShopService shopService;
    @Resource
    private IShopTypeService shopTypeService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    // ==================== 聊天主流程 ====================

    @Override
    public SseEmitter chat(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        String message = request == null ? null : request.getMessage();
        if (StrUtil.isBlank(message)) {
            sendError(emitter, "AGENT_EMPTY", "消息不能为空");
            safeComplete(emitter);
            return emitter;
        }
        // ThreadLocal 在异步线程中不可用，先取出用户
        UserDTO user = UserHolder.getUser();
        Long userId = user == null ? null : user.getId();
        String sessionId = request.getSessionId();
        final String msg = message.trim();
        EXECUTOR.execute(() -> runPipeline(emitter, msg, sessionId, userId));
        return emitter;
    }

    private void runPipeline(SseEmitter emitter, String message, String sessionId, Long userId) {
        try {
            // 0. 校验 API Key 是否已配置
            if (StrUtil.isBlank(aiProperties.getApiKey())) {
                sendError(emitter, "AGENT_NO_KEY", "AI 服务未配置，请先在 application.yaml 中填写 hmdp.ai.api-key");
                return;
            }

            // 1. 会话建立
            if (StrUtil.isBlank(sessionId)) {
                sessionId = "s_" + IdUtil.simpleUUID().substring(0, 12);
            }
            boolean isNew = !hasSession(sessionId);
            String owner = getSessionOwner(sessionId);
            // 归属校验：会话属于其他登录用户则拒绝；匿名用户不能继续他人会话
            if (owner != null && !owner.isEmpty() && (userId == null || !owner.equals(String.valueOf(userId)))) {
                sendError(emitter, "AGENT_FORBIDDEN", "无权访问该会话");
                return;
            }
            if (isNew) {
                createSession(sessionId, userId, message);
            }
            send(emitter, "session", new JSONObject().set("sessionId", sessionId));
            List<ChatMessage> history = loadMessages(sessionId);

            // 2. Agent1 意图识别
            long t1 = System.currentTimeMillis();
            sendAgent(emitter, 1, "running", "分析用户意图与约束条件...", 0, null);
            JSONObject intent = agent1ParseIntent(message, history);
            String intentSummary = StrUtil.isBlank(intent.getStr("summary")) ? "完成意图解析" : intent.getStr("summary");
            sendAgent(emitter, 1, "done", "识别意图：" + intentSummary, elapsed(t1), intent.toString());

            // 3. Agent2 搜索
            long t2 = System.currentTimeMillis();
            sendAgent(emitter, 2, "running", "检索候选商户...", 0, null);
            List<Shop> candidates = agent2Search(intent);
            sendAgent(emitter, 2, "done", "检索到 " + candidates.size() + " 家候选商户", elapsed(t2), null);

            // 4. Agent3 分析排序
            long t3 = System.currentTimeMillis();
            sendAgent(emitter, 3, "running", "按评分/销量/人均加权排序筛选...", 0, null);
            List<Shop> top = agent3Rank(candidates);
            sendAgent(emitter, 3, "done", "综合筛选出 Top" + top.size() + " 家", elapsed(t3), null);

            // 5. Agent4 推荐（DeepSeek 生成文案与理由）
            long t4 = System.currentTimeMillis();
            sendAgent(emitter, 4, "running", "生成推荐理由与候选文案...", 0, null);
            JSONObject recommend = agent4Recommend(top, intent, message, history);
            sendAgent(emitter, 4, "done", "已生成推荐文案", elapsed(t4), null);

            // 6. Agent5 汇总
            long t5 = System.currentTimeMillis();
            sendAgent(emitter, 5, "running", "整理最终回复...", 0, null);
            JSONObject result = new JSONObject();
            result.set("content", recommend.getStr("content", "抱歉，我没有找到合适的推荐。"));
            result.set("sources", recommend.getJSONArray("sources"));
            sendAgent(emitter, 5, "done", "汇总完成", elapsed(t5), null);

            // 7. 输出最终消息
            send(emitter, "message", result);
            send(emitter, "done", new JSONObject());

            // 8. 持久化消息
            appendMessages(sessionId,
                    new ChatMessage("user", message, System.currentTimeMillis()),
                    new ChatMessage("assistant", result.getStr("content"), System.currentTimeMillis()));
        } catch (Exception e) {
            log.error("AI 管家执行失败", e);
            sendError(emitter, "AGENT_INTERNAL", "AI 服务执行失败，请稍后重试");
        } finally {
            safeComplete(emitter);
        }
    }

    // ==================== Agent 各步骤 ====================

    /** Agent1 意图识别：DeepSeek 解析为结构化 JSON */
    private JSONObject agent1ParseIntent(String message, List<ChatMessage> history) {
        List<JSONObject> msgs = new ArrayList<>();
        msgs.add(systemMsg(buildIntentSystemPrompt()));
        // 注入最近的历史，支持追问
        int start = Math.max(0, history.size() - 4);
        for (int i = start; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            msgs.add(roleMsg("assistant".equals(m.getRole()) ? "assistant" : "user", m.getContent()));
        }
        msgs.add(roleMsg("user", message));
        String content = deepSeekClient.chat(msgs, true);
        return deepSeekClient.parseJsonObject(content);
    }

    /** Agent2 搜索：复用商城数据，按类型/名称关键词检索，支持人均上限过滤 */
    private List<Shop> agent2Search(JSONObject intent) {
        String intentType = intent.getStr("intent", "other");
        if ("voucher".equals(intentType)) {
            // 秒杀/代金券意图：候选为有可用券的商户
            return queryShopsWithVouchers();
        }
        String typeName = intent.getStr("typeName");
        String keyword = intent.getStr("keyword");
        Long priceMax = intent.getLong("priceMax");

        List<Shop> shops = new ArrayList<>();
        if (StrUtil.isNotBlank(typeName)) {
            ShopType type = shopTypeService.list().stream()
                    .filter(t -> StrUtil.contains(t.getName(), typeName) || StrUtil.contains(typeName, t.getName()))
                    .findFirst().orElse(null);
            if (type != null) {
                shops = shopService.lambdaQuery().eq(Shop::getTypeId, type.getId()).list();
            }
        }
        if (shops.isEmpty() && StrUtil.isNotBlank(keyword)) {
            shops = shopService.lambdaQuery().like(Shop::getName, keyword).list();
        }
        if (shops.isEmpty() && StrUtil.isBlank(typeName) && StrUtil.isBlank(keyword)) {
            shops = shopService.list();
        }
        if (priceMax != null && priceMax > 0) {
            shops = shops.stream()
                    .filter(s -> s.getAvgPrice() == null || s.getAvgPrice() <= priceMax)
                    .collect(Collectors.toList());
        }
        return shops;
    }

    /** Agent3 分析排序：评分高→销量高→人均低，取前 5 */
    private List<Shop> agent3Rank(List<Shop> shops) {
        return shops.stream()
                .sorted(Comparator
                        .comparing(Shop::getScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Shop::getSold, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Shop::getAvgPrice, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(5)
                .collect(Collectors.toList());
    }

    /** Agent4 推荐：DeepSeek 基于候选生成 markdown 文案；sources 由后端确定 */
    private JSONObject agent4Recommend(List<Shop> shops, JSONObject intent, String message, List<ChatMessage> history) {
        String intentType = intent.getStr("intent", "other");

        JSONObject payload = new JSONObject();
        payload.set("intent", intentType);
        payload.set("userMessage", message);
        if ("voucher".equals(intentType)) {
            payload.set("vouchers", buildVoucherList());
        }
        payload.set("shops", buildShopBriefList(shops));

        List<JSONObject> msgs = new ArrayList<>();
        msgs.add(systemMsg(buildRecommendSystemPrompt()));
        int start = Math.max(0, history.size() - 4);
        for (int i = start; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            msgs.add(roleMsg("assistant".equals(m.getRole()) ? "assistant" : "user", m.getContent()));
        }
        msgs.add(roleMsg("user", payload.toString()));

        String content = deepSeekClient.chat(msgs, false);

        JSONObject res = new JSONObject();
        res.set("content", StrUtil.isBlank(content) ? "抱歉，我没有找到合适的推荐。" : content);
        res.set("sources", buildSources(shops));
        return res;
    }

    /** 商户列表压缩为 LLM 上下文（剔除图片长串等无关字段） */
    private JSONArray buildShopBriefList(List<Shop> shops) {
        JSONArray arr = new JSONArray();
        for (Shop s : shops) {
            JSONObject o = new JSONObject();
            o.set("id", s.getId());
            o.set("name", s.getName());
            o.set("typeId", s.getTypeId());
            o.set("avgPrice", s.getAvgPrice());
            o.set("score", s.getScore() == null ? null : s.getScore() / 10.0);
            o.set("sold", s.getSold());
            o.set("area", s.getArea());
            o.set("address", s.getAddress());
            o.set("openHours", s.getOpenHours());
            arr.add(o);
        }
        return arr;
    }

    /** 可用代金券/秒杀券列表 */
    private JSONArray buildVoucherList() {
        JSONArray arr = new JSONArray();
        List<Voucher> vouchers = voucherService.list();
        for (Voucher v : vouchers) {
            if (v.getStatus() != null && v.getStatus() != 1) {
                continue;
            }
            Integer stock = null;
            if (v.getType() != null && v.getType() == 1) {
                SeckillVoucher sv = seckillVoucherService.getById(v.getId());
                stock = (sv == null || sv.getStock() == null) ? 0 : sv.getStock();
                if (stock <= 0) {
                    continue;
                }
            }
            Shop shop = shopService.getById(v.getShopId());
            JSONObject o = new JSONObject();
            o.set("id", v.getId());
            o.set("title", v.getTitle());
            o.set("subTitle", v.getSubTitle());
            o.set("payValue", v.getPayValue());
            o.set("actualValue", v.getActualValue());
            o.set("stock", stock);
            o.set("shopId", v.getShopId());
            o.set("shopName", shop == null ? "" : shop.getName());
            arr.add(o);
        }
        return arr;
    }

    /** 有可用券的商户（去重） */
    private List<Shop> queryShopsWithVouchers() {
        Set<Long> shopIds = new HashSet<>();
        for (Voucher v : voucherService.list()) {
            if (v.getStatus() != null && v.getStatus() != 1) {
                continue;
            }
            if (v.getType() != null && v.getType() == 1) {
                SeckillVoucher sv = seckillVoucherService.getById(v.getId());
                if (sv == null || sv.getStock() == null || sv.getStock() <= 0) {
                    continue;
                }
            }
            if (v.getShopId() != null) {
                shopIds.add(v.getShopId());
            }
        }
        if (shopIds.isEmpty()) {
            return new ArrayList<>();
        }
        return shopService.listByIds(shopIds);
    }

    /** 来源链接列表 */
    private JSONArray buildSources(List<Shop> shops) {
        JSONArray arr = new JSONArray();
        for (Shop s : shops) {
            arr.add(new JSONObject()
                    .set("name", s.getName())
                    .set("url", "/shop-detail.html?id=" + s.getId()));
        }
        return arr;
    }

    // ==================== 会话历史 ====================

    @Override
    public Result listSessions() {
        Long userId = UserHolder.getUser().getId();
        Set<String> sids = stringRedisTemplate.opsForZSet().reverseRange(userSessionsKey(userId), 0, -1);
        List<SessionInfo> list = new ArrayList<>();
        if (sids != null) {
            for (String sid : sids) {
                Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(sessionKey(sid));
                if (map.isEmpty()) {
                    continue;
                }
                SessionInfo info = new SessionInfo();
                info.setSessionId(sid);
                info.setSummary(map.getOrDefault("summary", "").toString());
                info.setCreateTime(Long.valueOf(map.getOrDefault("createTime", "0").toString()));
                list.add(info);
            }
        }
        return Result.ok(list);
    }

    @Override
    public Result listMessages(String sessionId) {
        Long userId = UserHolder.getUser().getId();
        if (!checkOwner(sessionId, userId)) {
            return Result.fail("无权访问该会话");
        }
        return Result.ok(loadMessages(sessionId));
    }

    @Override
    public Result deleteSession(String sessionId) {
        Long userId = UserHolder.getUser().getId();
        if (!checkOwner(sessionId, userId)) {
            return Result.fail("无权访问该会话");
        }
        stringRedisTemplate.delete(sessionKey(sessionId));
        stringRedisTemplate.opsForZSet().remove(userSessionsKey(userId), sessionId);
        return Result.ok();
    }

    // ==================== Redis 会话存储 ====================

    private String sessionKey(String sessionId) {
        return SESSION_KEY + sessionId;
    }

    private String userSessionsKey(Long userId) {
        return USER_SESSIONS_KEY + userId + ":sessions";
    }

    private boolean hasSession(String sessionId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(sessionKey(sessionId)));
    }

    private String getSessionOwner(String sessionId) {
        Object v = stringRedisTemplate.opsForHash().get(sessionKey(sessionId), "userId");
        return v == null ? null : v.toString();
    }

    private void createSession(String sessionId, Long userId, String firstMsg) {
        Map<String, String> map = new HashMap<>();
        map.put("userId", userId == null ? "" : String.valueOf(userId));
        map.put("createTime", String.valueOf(System.currentTimeMillis()));
        map.put("summary", StrUtil.maxLength(firstMsg, 30));
        map.put("messages", "[]");
        stringRedisTemplate.opsForHash().putAll(sessionKey(sessionId), map);
        if (userId != null) {
            stringRedisTemplate.opsForZSet().add(userSessionsKey(userId), sessionId, System.currentTimeMillis());
        }
    }

    private void appendMessages(String sessionId, ChatMessage... msgs) {
        Object v = stringRedisTemplate.opsForHash().get(sessionKey(sessionId), "messages");
        JSONArray arr = (v == null || StrUtil.isBlank(v.toString()))
                ? new JSONArray() : JSONUtil.parseArray(v.toString());
        for (ChatMessage m : msgs) {
            arr.add(new JSONObject()
                    .set("role", m.getRole())
                    .set("content", m.getContent())
                    .set("time", m.getTime()));
        }
        stringRedisTemplate.opsForHash().put(sessionKey(sessionId), "messages", arr.toString());
    }

    private List<ChatMessage> loadMessages(String sessionId) {
        Object v = stringRedisTemplate.opsForHash().get(sessionKey(sessionId), "messages");
        if (v == null || StrUtil.isBlank(v.toString())) {
            return new ArrayList<>();
        }
        JSONArray arr = JSONUtil.parseArray(v.toString());
        List<ChatMessage> list = new ArrayList<>();
        for (Object o : arr) {
            JSONObject jo = (JSONObject) o;
            list.add(new ChatMessage(jo.getStr("role"), jo.getStr("content"), jo.getLong("time")));
        }
        return list;
    }

    private boolean checkOwner(String sessionId, Long userId) {
        if (StrUtil.isBlank(sessionId)) {
            return false;
        }
        String owner = getSessionOwner(sessionId);
        return owner != null && !owner.isEmpty() && owner.equals(String.valueOf(userId));
    }

    // ==================== Prompt 构造 ====================

    private String buildIntentSystemPrompt() {
        List<ShopType> types = shopTypeService.list();
        StringBuilder sb = new StringBuilder();
        for (ShopType t : types) {
            sb.append(t.getId()).append("=").append(t.getName()).append("，");
        }
        return "你是商城「智能管家」的意图识别 Agent。商城只有两类数据：商户(tb_shop：名称/类型/人均/评分/销量/营业时间/地址) 和 代金券/秒杀券(tb_voucher)。" +
                "可用的商户类型：[" + sb + "]。\n" +
                "请把用户消息解析为 JSON 对象，必须包含字段：\n" +
                "- intent: 字符串，\"shop_recommend\"(推荐商户) 或 \"voucher\"(查询代金券/秒杀) 或 \"other\"(其他)\n" +
                "- typeName: 匹配到的商户类型名称(如\"美食\"\"KTV\")，没有则为 null\n" +
                "- keyword: 商户名称关键词(如\"火锅\")，没有则为 null\n" +
                "- priceMax: 人均价格上限(整数，如 200)，没有则为 null\n" +
                "- scene: 场景说明字符串(如\"周末聚餐\"\"家庭聚会\")，没有则为空串\n" +
                "- followUp: 布尔，用户是否在追问上一轮结果(如\"还有吗\"\"第一家怎么样\")\n" +
                "- summary: 字符串，用 | 拼接的一句话摘要(如\"聚餐推荐 | 人均<200 | 周末\")，供前端展示\n" +
                "只返回该 JSON 对象，不要任何多余文字。";
    }

    private String buildRecommendSystemPrompt() {
        return "你是商城「智能管家」的推荐与文案 Agent。根据给定的候选数据(JSON)和用户消息，生成一份简洁、可读的 markdown 推荐回复。\n" +
                "要求：\n" +
                "1. 使用 markdown 格式、中文，先一句话总述，再逐条列出。\n" +
                "2. 每条商户推荐必须包含：商户名、评分(如 ⭐4.8)、人均(如 人均￥150)、一行推荐理由，并在行尾附 [查看详情](/shop-detail.html?id=xx)，链接 id 用候选数据里给出的 id。\n" +
                "3. 若是代金券/秒杀查询(vouchers)，列出券标题、面额(payValue 单位是分，换算成元)、剩余库存，并给出发券商户的详情链接。\n" +
                "4. 若候选数据为空，则友好说明没有找到合适结果，并给出搜索建议(如换个关键词、调高预算)。\n" +
                "5. 直接输出 markdown 正文，不要输出 JSON，不要输出\"好的\"\"以下是\"之类废话。";
    }

    private JSONObject systemMsg(String content) {
        return roleMsg("system", content);
    }

    private JSONObject roleMsg(String role, String content) {
        return new JSONObject().set("role", role).set("content", content);
    }

    // ==================== SSE 事件发送 ====================

    private void sendAgent(SseEmitter emitter, int agentId, String status, String description, long duration, String output) {
        JSONObject ev = new JSONObject();
        ev.set("agentId", agentId);
        ev.set("name", AGENT_NAMES[agentId - 1]);
        ev.set("icon", AGENT_ICONS[agentId - 1]);
        ev.set("status", status);
        ev.set("description", description);
        if (duration > 0) {
            ev.set("duration", duration);
        }
        if (output != null) {
            ev.set("output", output);
        }
        send(emitter, "agent", ev);
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            String json = data instanceof String ? (String) data : JSONUtil.toJsonStr(data);
            emitter.send(SseEmitter.event().name(event).data(json));
        } catch (Exception e) {
            log.debug("SSE 发送失败(客户端可能已断开): {}", e.getMessage());
        }
    }

    private void sendError(SseEmitter emitter, String code, String message) {
        JSONObject o = new JSONObject();
        o.set("code", code);
        o.set("message", message);
        send(emitter, "error", o);
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
