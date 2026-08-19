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
 * Agent业务编排管道类
 * 完整执行链路：LLM意图识别 -> DB商户搜索 -> 预算过滤+排序分析 -> TopN商户推荐 -> LLM汇总生成回复文案
 * <p>
 * SSE事件与前端约定：
 * session：会话初始化事件，返回sessionId
 * agent：Agent节点状态事件，包含RUNNING/DONE状态，用于前端展示步骤进度
 * message：最终输出消息事件，携带回复内容和引用来源
 * done：整个Agent流程正常结束事件
 * error：异常事件，Agent执行出错时推送
 * </p>
 */
@Slf4j
@Component
public class AgentPipeline {

    /** 最终输出推荐商户数量 */
    private static final int TOP_N = 3;
    /** 根据商户类型检索时，向后翻取的页数，获取更多候选商户 */
    private static final int TYPE_PAGES = 3;

    /** DeepSeek大模型客户端，调用LLM能力 */
    @Resource
    private DeepSeekClient llm;

    /** 商户业务Service */
    @Resource
    private IShopService shopService;

    /** 商户分类业务Service */
    @Resource
    private IShopTypeService shopTypeService;

    /** Jackson序列化工具，解析大模型返回的JSON字符串 */
    @Resource
    private ObjectMapper objectMapper;


    /**
     * Agent主执行入口，完整执行Agent流水线，通过SSE流式推送执行状态和结果给前端
     *
     * @param req     用户Agent聊天请求，携带用户输入消息、sessionId
     * @param emitter SSE长连接会话对象，用于向前端推送流式事件
     */
    public void run(AgentChatRequest req, SseEmitter emitter) {
        // 生成会话ID，如果请求未携带则自动生成简短UUID作为sessionId
        String sessionId = StrUtil.isBlank(req.getSessionId())
                ? "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                : req.getSessionId();
        // 推送session事件，告知前端本次会话编号
        send(emitter, "session", Collections.singletonMap("sessionId", sessionId));

        try {
            // ==========步骤1：意图识别Agent，调用LLM解析用户需求，提取分类、预算、关键词等信息 ==========
            emitAgent(emitter, 1, "意图识别Agent", "cpu", AgentStatus.RUNNING, "解析用户意图...", null);
            long start = System.currentTimeMillis();
            // 调用LLM解析用户输入，拿到结构化意图结果
            Map<String, Object> intent = recognizeIntent(req.getMessage());
            // 推送意图识别完成状态，附带执行耗时
            emitAgent(emitter, 1, "意图识别Agent", "cpu", AgentStatus.DONE, intentDesc(intent), elapsed(start));

            // ==========步骤2：搜索Agent，根据解析出来的意图从数据库查询候选商户 ==========
            emitAgent(emitter, 2, "搜索Agent", "search", AgentStatus.RUNNING, "检索候选商户...", null);
            start = System.currentTimeMillis();
            List<Shop> shops = searchShops(intent);
            emitAgent(emitter, 2, "搜索Agent", "search", AgentStatus.DONE, "检索到 " + shops.size() + " 家候选商户", elapsed(start));

            // 数据库没有查到任何商户，直接结束流程返回提示
            if (shops.isEmpty()) {
                send(emitter, "message", messageVO("抱歉，没有找到符合条件的商户。", Collections.emptyList()));
                send(emitter, "done", Collections.emptyMap());
                return;
            }

            // ==========步骤3：分析Agent，执行预算过滤、商户排序逻辑 ==========
            emitAgent(emitter, 3, "分析Agent", "data-line", AgentStatus.RUNNING, "按评分/预算筛选排序...", null);
            start = System.currentTimeMillis();
            List<Shop> ranked = rankShops(shops, toInt(intent.get("budget")));
            emitAgent(emitter, 3, "分析Agent", "data-line", AgentStatus.DONE, "筛选后 " + ranked.size() + " 家", elapsed(start));

            // 过滤之后没有符合预算条件商户，直接返回提示结束
            if (ranked.isEmpty()) {
                send(emitter, "message", messageVO("预算范围内没有找到合适的商户。", Collections.emptyList()));
                send(emitter, "done", Collections.emptyMap());
                return;
            }

            // ==========步骤4：推荐Agent，截取TopN最优商户作为推荐结果 ==========
            emitAgent(emitter, 4, "推荐Agent", "medal-1", AgentStatus.RUNNING, "生成 TopN 推荐...", null);
            start = System.currentTimeMillis();
            List<Shop> top = ranked.subList(0, Math.min(TOP_N, ranked.size()));
            emitAgent(emitter, 4, "推荐Agent", "medal-1", AgentStatus.DONE, "推荐 Top" + top.size(), elapsed(start));

            // ==========步骤5：汇总Agent，把Top商户交给LLM生成Markdown格式自然语言回复 ==========
            emitAgent(emitter, 5, "汇总Agent", "document-copy", AgentStatus.RUNNING, "生成 markdown 文案...", null);
            start = System.currentTimeMillis();
            String markdown = summarize(req.getMessage(), top);
            emitAgent(emitter, 5, "汇总Agent", "document-copy", AgentStatus.DONE, "已生成回复", elapsed(start));

            // 推送最终消息内容、引用来源，推送done事件标志整个Agent流程结束
            send(emitter, "message", messageVO(markdown, buildSources(top)));
            send(emitter, "done", Collections.emptyMap());
        } catch (Exception e) {
            log.error("agent pipeline error", e);
            // 捕获全部异常，向前端推送SSE error事件，Agent执行异常
            send(emitter, "error", new AgentErrorVO("AGENT_INTERNAL_ERROR", "服务异常"));
        }
    }

    /**
     * LLM意图识别：调用大模型JSON模式解析用户输入，提取typeId、typeName、budget、keyword
     * 并且校验LLM输出的typeId是否真实存在数据库，防止幻觉输出不存在的商户分类ID
     *
     * @param message 用户原始提问
     * @return 结构化意图Map，包含typeId、typeName、budget、keyword
     */
    private Map<String, Object> recognizeIntent(String message) {
        // 查询数据库全部商户分类，用于Prompt构造，同时校验LLM返回typeId合法性
        List<ShopType> types = shopTypeService.query().orderByAsc("sort").list();
        // 调用大模型获取JSON字符串，解析为Map
        Map<String, Object> parsed = parseJson(llm.chatJson(Prompts.intentSystem(types), "用户需求:" + message));

        Integer typeId = toInt(parsed.get("typeId"));
        String keyword = parsed.get("keyword") instanceof String ? (String) parsed.get("keyword") : null;

        ShopType matched = null;
        // 校验大模型输出typeId是否真实存在数据库，规避LLM幻觉
        if (typeId != null) {
            matched = types.stream()
                    .filter(t -> t.getId() != null && t.getId().intValue() == typeId)
                    .findFirst().orElse(null);
        }

        Map<String, Object> intent = new HashMap<>();
        // typeId合法就存入，不合法置null，后续降级走关键词搜索
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

    /**
     * 商户搜索逻辑
     * 当typeId有效：按商户分类分页查询多页，拿到一批候选商户
     * typeId无效：降级，使用关键词模糊匹配商户名称搜索
     *
     * @param intent 识别得到的结构化意图
     * @return 候选商户集合
     */
    private List<Shop> searchShops(Map<String, Object> intent) {
        Integer typeId = toInt(intent.get("typeId"));
        // 如果分类ID有效，按分类多页查询
        if (typeId != null && typeId > 0) {
            List<Shop> shops = new ArrayList<>();
            for (int page = 1; page <= TYPE_PAGES; page++) {
                List<Shop> batch = shopService.query()
                        .eq("type_id", typeId)
                        .page(new Page<>(page, SystemConstants.DEFAULT_PAGE_SIZE))
                        .getRecords();
                // 当前页无数据直接跳出循环
                if (batch.isEmpty()) {
                    break;
                }
                shops.addAll(batch);
            }
            return shops;
        }
        // 分类无效，走关键词搜索分支
        String keyword = (String) intent.get("keyword");
        if (StrUtil.isBlank(keyword)) {
            return Collections.emptyList();
        }
        return shopService.query()
                .like("name", keyword)
                .page(new Page<>(1, SystemConstants.MAX_PAGE_SIZE))
                .getRecords();
    }

    /**
     * 商户分析：预算过滤 + 排序
     * 1. 如果传入预算，过滤掉人均消费超出预算的商户
     * 2. 排序优先按照评分降序，评分相同按销量降序
     * null安全处理，防止字段为空空指针
     *
     * @param shops 原始候选商户列表
     * @param budget 用户识别出来的人均预算，允许null代表不限预算
     * @return 过滤+排序完成的商户列表
     */
    private List<Shop> rankShops(List<Shop> shops, Integer budget) {
        List<Shop> filtered = shops;
        // 如果有预算，过滤人均价格大于预算的商户
        if (budget != null && budget > 0) {
            filtered = shops.stream()
                    .filter(s -> s.getAvgPrice() != null && s.getAvgPrice() <= budget)
                    .collect(Collectors.toList());
        }
        // 评分降序，再销量降序；nullsFirst处理字段为null的情况
        return filtered.stream()
                .sorted(Comparator
                        .comparing(Shop::getScore, Comparator.nullsFirst(Comparator.naturalOrder())).reversed()
                        .thenComparing(Shop::getSold, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .collect(Collectors.toList());
    }

    /**
     * LLM汇总生成Markdown回复文案
     * 将用户原始提问 + TopN商户信息组装prompt交给大模型生成自然语言回答
     *
     * @param message 用户原始提问
     * @param top     筛选完成的TopN商户
     * @return LLM输出markdown格式回复文本
     */
    private String summarize(String message, List<Shop> top) {
        String user = "用户需求:" + message + "\nTop 商户:\n" + shopsToText(top);
        return llm.chat(Prompts.summarizeSystem(), user);
    }

    /**
     * 将Shop列表转为纯文本，用于喂给LLM做汇总，格式化每一条商户基础信息
     *
     * @param shops 商户列表
     * @return 拼接好的多行文本
     */
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

    /**
     * 构造前端引用来源VO，用于页面展示参考商户链接
     *
     * @param top Top推荐商户
     * @return AgentSourceVO列表，包含商户名称、跳转详情页url
     */
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

    /**
     * 组装消息输出VO对象，封装回复内容和引用来源
     *
     * @param content 回复文本/markdown
     * @param sources 引用商户来源
     * @return AgentMessageVO
     */
    private AgentMessageVO messageVO(String content, List<AgentSourceVO> sources) {
        AgentMessageVO vo = new AgentMessageVO();
        vo.setContent(content);
        vo.setSources(sources);
        return vo;
    }

    /**
     * 推送Agent节点状态SSE事件，用于前端渲染每一步Agent运行状态
     *
     * @param emitter     SSE会话
     * @param agentId     Agent步骤编号 1~5
     * @param name        Agent名称
     * @param icon        前端图标标识
     * @param status      RUNNING / DONE
     * @param description 步骤描述文本
     * @param duration    执行耗时毫秒，可为null
     */
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


    /**
     * SSE消息发送通用工具方法
     * 捕获IOException，代表客户端主动断开连接，仅打印warn日志，不向上抛出异常打断Agent流程
     *
     * @param emitter SSE会话
     * @param event   SSE事件名称，前端EventSource监听使用
     * @param data    需要推送的数据对象，Spring自动序列化JSON
     */
    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            log.warn("agent sse send failed, client disconnected: {}", event, e);
        }
    }

    /**
     * 把意图Map转为可读描述文本，用于SSE推送展示给前端
     *
     * @param intent 结构化意图
     * @return 拼接好的意图描述字符串
     */
    private String intentDesc(Map<String, Object> intent) {
        String typeName = intent.get("typeName") == null ? "未知" : intent.get("typeName").toString();
        Integer budget = toInt(intent.get("budget"));
        return "识别意图:" + typeName + (budget == null ? " | 不限预算" : " | 人均≤" + budget);
    }

    /**
     * 计算方法执行耗时
     *
     * @param start 方法开始时间戳 System.currentTimeMillis()
     * @return 消耗毫秒数
     */
    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    /**
     * 通用对象安全转Integer工具
     * 兼容数字、字符串数字，转换失败返回null
     *
     * @param v 待转换对象
     * @return Integer值，失败/空输入返回null
     */
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

    /**
     * 解析LLM输出JSON字符串为Map，自动剥离markdown ```json ```代码块标记
     * 解析异常返回空map，不抛异常，防止大模型输出格式错乱导致整个Agent崩溃
     *
     * @param text LLM返回原始字符串，可能包裹markdown代码块
     * @return 解析完成Map，解析失败返回空集合
     */
    private Map<String, Object> parseJson(String text) {
        text = text == null ? "" : text.trim();
        // 剥离markdown代码块标记 ```json ... ```
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