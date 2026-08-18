package com.hmdp.agent;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.client.DeepSeekClient;
import com.hmdp.agent.prompt.Prompts;
import com.hmdp.dto.agent.AgentChatRequest;
import com.hmdp.dto.agent.AgentErrorVO;
import com.hmdp.dto.agent.AgentMessageVO;
import com.hmdp.dto.agent.AgentSourceVO;
import com.hmdp.dto.agent.AgentStatusVO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.enums.AgentStatus;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 智能体编排:意图识别(LLM) -> 搜索(DB) -> 分析(代码排序) -> 推荐(取TopN) -> 汇总(LLM文案)。
 *
 * <p>SSE 事件顺序与前端约定一致:session -> agent(running/done 成对) -> message -> done,异常发 error。</p>
 */
@Slf4j
@Component
public class AgentPipeline {

    /** 最终推荐的商户数量 */
    private static final int TOP_N = 3;
    /** 按类型检索时翻页数 */
    private static final int TYPE_PAGES = 3;

    @Resource
    private DeepSeekClient llm;

    @Resource
    private IShopService shopService;

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private ObjectMapper objectMapper;

    public void run(AgentChatRequest req, SseEmitter emitter) {
        String sessionId = StrUtil.isBlank(req.getSessionId())
                ? "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                : req.getSessionId();
        send(emitter, "session", Collections.singletonMap("sessionId", sessionId));

        try {
            // 1. 意图识别(LLM)
            emitAgent(emitter, 1, "意图识别Agent", "cpu", AgentStatus.RUNNING, "解析用户意图...", null);
            long start = System.currentTimeMillis();
            Map<String, Object> intent = recognizeIntent(req.getMessage());
            emitAgent(emitter, 1, "意图识别Agent", "cpu", AgentStatus.DONE, intentDesc(intent), elapsed(start));

            // 2. 搜索(DB 按类型/名称)
            emitAgent(emitter, 2, "搜索Agent", "search", AgentStatus.RUNNING, "检索候选商户...", null);
            start = System.currentTimeMillis();
            List<Shop> shops = searchShops(intent);
            emitAgent(emitter, 2, "搜索Agent", "search", AgentStatus.DONE, "检索到 " + shops.size() + " 家候选商户", elapsed(start));

            if (shops.isEmpty()) {
                send(emitter, "message", messageVO("抱歉，没有找到符合条件的商户。", Collections.emptyList()));
                send(emitter, "done", Collections.emptyMap());
                return;
            }

            // 3. 分析(代码:预算过滤 + 评分/销量排序)
            emitAgent(emitter, 3, "分析Agent", "data-line", AgentStatus.RUNNING, "按评分/预算筛选排序...", null);
            start = System.currentTimeMillis();
            List<Shop> ranked = rankShops(shops, toInt(intent.get("budget")));
            emitAgent(emitter, 3, "分析Agent", "data-line", AgentStatus.DONE, "筛选后 " + ranked.size() + " 家", elapsed(start));

            if (ranked.isEmpty()) {
                send(emitter, "message", messageVO("预算范围内没有找到合适的商户。", Collections.emptyList()));
                send(emitter, "done", Collections.emptyMap());
                return;
            }

            // 4. 推荐(代码:取 TopN)
            emitAgent(emitter, 4, "推荐Agent", "medal-1", AgentStatus.RUNNING, "生成 TopN 推荐...", null);
            start = System.currentTimeMillis();
            List<Shop> top = ranked.subList(0, Math.min(TOP_N, ranked.size()));
            emitAgent(emitter, 4, "推荐Agent", "medal-1", AgentStatus.DONE, "推荐 Top" + top.size(), elapsed(start));

            // 5. 汇总(LLM:基于 Top 商户生成文案)
            emitAgent(emitter, 5, "汇总Agent", "document-copy", AgentStatus.RUNNING, "生成 markdown 文案...", null);
            start = System.currentTimeMillis();
            String markdown = summarize(req.getMessage(), top);
            emitAgent(emitter, 5, "汇总Agent", "document-copy", AgentStatus.DONE, "已生成回复", elapsed(start));

            send(emitter, "message", messageVO(markdown, buildSources(top)));
            send(emitter, "done", Collections.emptyMap());
        } catch (Exception e) {
            log.error("agent pipeline error", e);
            send(emitter, "error", new AgentErrorVO("AGENT_INTERNAL_ERROR", "服务异常"));
        }
    }

    /** 意图识别:LLM JSON mode 解析 -> {typeId,typeName,budget,keyword},并校验 typeId 属于真实类型。 */
    private Map<String, Object> recognizeIntent(String message) {
        List<ShopType> types = shopTypeService.query().orderByAsc("sort").list();
        Map<String, Object> parsed = parseJson(llm.chatJson(Prompts.intentSystem(types), "用户需求:" + message));

        Integer typeId = toInt(parsed.get("typeId"));
        String keyword = parsed.get("keyword") instanceof String ? (String) parsed.get("keyword") : null;

        // 校验:typeId 必须存在于类型列表,否则回退 keyword 搜索
        ShopType matched = null;
        if (typeId != null) {
            matched = types.stream()
                    .filter(t -> t.getId() != null && t.getId().intValue() == typeId)
                    .findFirst().orElse(null);
        }

        Map<String, Object> intent = new HashMap<>();
        if (matched != null) {
            intent.put("typeId", matched.getId());
            intent.put("typeName", matched.getName());
        } else {
            intent.put("typeId", null);
            intent.put("typeName", null);
        }
        intent.put("budget", toInt(parsed.get("budget")));
        intent.put("keyword", keyword);
        return intent;
    }

    /** 搜索:typeId 有效时按类型翻页取候选;否则按名称关键字搜索。 */
    private List<Shop> searchShops(Map<String, Object> intent) {
        Integer typeId = toInt(intent.get("typeId"));
        if (typeId != null && typeId > 0) {
            List<Shop> shops = new ArrayList<>();
            for (int page = 1; page <= TYPE_PAGES; page++) {
                List<Shop> batch = shopService.query()
                        .eq("type_id", typeId)
                        .page(new Page<>(page, SystemConstants.DEFAULT_PAGE_SIZE))
                        .getRecords();
                if (batch.isEmpty()) {
                    break;
                }
                shops.addAll(batch);
            }
            return shops;
        }
        String keyword = (String) intent.get("keyword");
        if (StrUtil.isBlank(keyword)) {
            return Collections.emptyList();
        }
        return shopService.query()
                .like("name", keyword)
                .page(new Page<>(1, SystemConstants.MAX_PAGE_SIZE))
                .getRecords();
    }

    /** 分析:先按预算过滤,再按评分降序、销量降序排序。 */
    private List<Shop> rankShops(List<Shop> shops, Integer budget) {
        List<Shop> filtered = shops;
        if (budget != null && budget > 0) {
            filtered = shops.stream()
                    .filter(s -> s.getAvgPrice() != null && s.getAvgPrice() <= budget)
                    .collect(Collectors.toList());
        }
        return filtered.stream()
                .sorted(Comparator
                        .comparing(Shop::getScore, Comparator.nullsFirst(Comparator.naturalOrder())).reversed()
                        .thenComparing(Shop::getSold, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .collect(Collectors.toList());
    }

    /** 汇总:LLM 基于 Top 商户生成 markdown 文案。 */
    private String summarize(String message, List<Shop> top) {
        String user = "用户需求:" + message + "\nTop 商户:\n" + shopsToText(top);
        return llm.chat(Prompts.summarizeSystem(), user);
    }

    private String shopsToText(List<Shop> shops) {
        return shops.stream()
                .map(s -> String.format("- id=%d %s 评分%.1f 人均%d元 销量%d 商圈%s",
                        s.getId(), s.getName(),
                        (s.getScore() == null ? 0 : s.getScore()) / 10.0,
                        s.getAvgPrice() == null ? 0 : s.getAvgPrice(),
                        s.getSold() == null ? 0 : s.getSold(),
                        s.getArea()))
                .collect(Collectors.joining("\n"));
    }

    private List<AgentSourceVO> buildSources(List<Shop> top) {
        return top.stream()
                .map(s -> {
                    AgentSourceVO vo = new AgentSourceVO();
                    vo.setName(s.getName());
                    vo.setUrl("/shop-detail.html?id=" + s.getId());
                    return vo;
                })
                .collect(Collectors.toList());
    }

    private AgentMessageVO messageVO(String content, List<AgentSourceVO> sources) {
        AgentMessageVO vo = new AgentMessageVO();
        vo.setContent(content);
        vo.setSources(sources);
        return vo;
    }

    private void emitAgent(SseEmitter emitter, int agentId, String name, String icon,
                           AgentStatus status, String description, Long duration) {
        AgentStatusVO vo = new AgentStatusVO();
        vo.setAgentId(agentId);
        vo.setName(name);
        vo.setIcon(icon);
        vo.setStatus(status);
        vo.setDescription(description);
        vo.setDuration(duration);
        send(emitter, "agent", vo);
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            log.warn("agent sse send failed, client disconnected: {}", event, e);
        }
    }

    private String intentDesc(Map<String, Object> intent) {
        String typeName = intent.get("typeName") == null ? "未知" : intent.get("typeName").toString();
        Integer budget = toInt(intent.get("budget"));
        return "识别意图:" + typeName + (budget == null ? " | 不限预算" : " | 人均≤" + budget);
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private Integer toInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Integer) {
            return (Integer) v;
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> parseJson(String text) {
        text = text == null ? "" : text.trim();
        if (text.startsWith("```")) {
            text = text.replace("`", "").trim();
            if (text.startsWith("json")) {
                text = text.substring(4).trim();
            }
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            if (!node.isObject()) {
                return Collections.emptyMap();
            }
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            log.warn("parse llm json failed: {}", text, e);
            return Collections.emptyMap();
        }
    }
}
