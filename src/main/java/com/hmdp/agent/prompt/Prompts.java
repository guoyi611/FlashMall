package com.hmdp.agent.prompt;

import cn.hutool.core.util.StrUtil;
import com.hmdp.entity.ShopType;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent prompt 常量与拼装。
 */
public final class Prompts {

    /** 防幻觉约束:要求 LLM 只用真实商户列表,不编造。 */
    private static final String NO_HALLUCINATION =
            "【严格约束】只能使用「Top 商户」列表中的商户，严禁编造或推荐列表之外的商户；"
                    + "每家商户的 id、名称、评分、人均必须与列表完全一致。";

    private Prompts() {
    }

    /** 理解+路由 system prompt:候选商户类型 + 上次推荐意图 -> 结构化 JSON(action + typeId/budget/keyword)。 */
    public static String understandSystem(List<ShopType> types, String lastIntentCtx) {
        String typeLines = types.stream()
                .map(t -> t.getId() + " " + t.getName())
                .collect(Collectors.joining("\n"));
        String lastCtx = StrUtil.isBlank(lastIntentCtx)
                ? "无(首次对话或尚未生成过推荐)"
                : lastIntentCtx;
        return "你是意图识别Agent。判断用户本次是「推荐商户」还是「追问上文对话」，并从下面的商户类型列表里选择最匹配的一个类型，"
                + "提取预算、关键词等约束。只输出 JSON，不要输出其他内容。\n"
                + "商户类型列表:\n" + typeLines + "\n"
                + "上次推荐的条件(仅当用户是在调整这些条件时参考，不得臆测未提及的字段):\n" + lastCtx + "\n"
                + "判断规则:\n"
                + "- 用户在上文基础上调整条件(如“再便宜一点”、“换个评分更高的”)，action 为 recommend，"
                + "且 budget/typeId 必须以上次条件为基础增减，不得凭空臆测\n"
                + "- 用户只是追问上文出现的商户(如“我第一次吃的是哪家店”)，action 为 chat，"
                + "typeId/typeName/budget/keyword 均输出 null\n"
                + "- 其余情况 action 为 recommend\n"
                + "JSON 格式:{\"action\":\"recommend\"或\"chat\",\"typeId\":整数或null,\"typeName\":\"名称或null\","
                + "\"budget\":整数或null,\"keyword\":字符串或null}\n"
                + "注意:用户没有明确提到预算时，budget 必须为 null，严禁臆测数值。";
    }

    /** 汇总 system prompt:基于 Top 商户生成 markdown 推荐文案。 */
    public static String summarizeSystem() {
        return "你是汇总Agent。根据用户需求和已筛选出的 Top 商户，生成给用户看的 markdown 推荐文案："
                + "每家商户一行，包含名称、评分(满分5)、人均(元)和一句推荐理由，"
                + "并用链接 [查看详情](/shop-detail.html?id=商户id) 结尾。"
                + NO_HALLUCINATION;
    }

    /** 追问回答 system prompt:只依据上文对话中出现过的商户回答，不搜新商户。 */
    public static String chatSystem() {
        return "你是对话Agent。用户在追问上文对话中提到的商户信息。"
                + "请只依据上文对话中出现过的商户回答，严禁编造或补充上文中不存在的商户；"
                + "若上文没有相关信息，如实告知用户，不要猜测。"
                + NO_HALLUCINATION;
    }
}
