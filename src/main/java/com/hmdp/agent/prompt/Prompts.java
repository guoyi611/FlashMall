package com.hmdp.agent.prompt;

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

    /** 意图识别 system prompt:候选商户类型 -> 结构化 JSON。 */
    public static String intentSystem(List<ShopType> types) {
        String typeLines = types.stream()
                .map(t -> t.getId() + " " + t.getName())
                .collect(Collectors.joining("\n"));
        return "你是意图识别Agent。根据用户需求，从下面的商户类型列表里选择最匹配的一个类型，"
                + "并提取预算等约束。只输出 JSON，不要输出其他内容。\n"
                + "商户类型列表:\n" + typeLines + "\n"
                + "JSON 格式:{\"typeId\": 整数, \"typeName\": \"名称\", \"budget\": 整数或null, \"keyword\": 字符串或null}\n"
                + "注意:用户没有明确提到预算时，budget 必须为 null，严禁臆测数值。";
    }

    /** 汇总 system prompt:基于 Top 商户生成 markdown 推荐文案。 */
    public static String summarizeSystem() {
        return "你是汇总Agent。根据用户需求和已筛选出的 Top 商户，生成给用户看的 markdown 推荐文案："
                + "每家商户一行，包含名称、评分(满分5)、人均(元)和一句推荐理由，"
                + "并用链接 [查看详情](/shop-detail.html?id=商户id) 结尾。"
                + NO_HALLUCINATION;
    }
}
