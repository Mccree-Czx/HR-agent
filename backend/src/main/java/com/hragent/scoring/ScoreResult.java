package com.hragent.scoring;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 评分结果(评审 P2-11:结构化输出,字段级校验在 fromJson 中完成)。
 * pending=true 表示因前置门禁(如期望职能待确认)无法定论,须置 PENDING 且绝不判为通过。
 */
public record ScoreResult(
        int score,
        boolean pass,
        boolean pending,
        String summary,
        List<String> reasons) {

    /** 缺省通过门槛(既有调用点不传阈值时的向后兼容默认值) */
    public static final int DEFAULT_PASS_THRESHOLD = 60;

    /**
     * 从模型输出 JSON 解析并校验(使用默认门槛 {@link #DEFAULT_PASS_THRESHOLD})。
     */
    public static ScoreResult fromJson(JsonNode node) {
        return fromJson(node, DEFAULT_PASS_THRESHOLD);
    }

    /**
     * 从模型输出 JSON 解析并校验。
     * 非法结构/越界分数/缺失字段均抛出 IllegalArgumentException,由调用方决定重试或放弃。
     * 硬规则(评审 Important-2):最终 pass = 模型 pass && score >= passThreshold,
     * 即模型判过但分数低于门槛时**降级为不通过**,门槛由岗位确认值决定。
     */
    public static ScoreResult fromJson(JsonNode node, int passThreshold) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("评分输出不是 JSON 对象");
        }
        JsonNode scoreNode = node.get("score");
        if (scoreNode == null || !scoreNode.isInt()) {
            throw new IllegalArgumentException("评分输出缺少合法 score 字段");
        }
        int score = scoreNode.asInt();
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score 越界: " + score);
        }
        JsonNode passNode = node.get("pass");
        if (passNode == null || !passNode.isBoolean()) {
            throw new IllegalArgumentException("评分输出缺少合法 pass 字段");
        }
        boolean modelPass = passNode.asBoolean();
        if (score >= passThreshold && !modelPass) {
            throw new IllegalArgumentException("score 达阈值但 pass=false,输出矛盾");
        }
        // 硬规则:模型判过但未达门槛 → 降级为不通过(门槛不得被模型布尔值绕过)
        boolean pass = modelPass && score >= passThreshold;
        String summary = node.has("summary") ? node.get("summary").asText("") : "";
        List<String> reasons = new ArrayList<>();
        if (node.has("reasons") && node.get("reasons").isArray()) {
            for (JsonNode r : node.get("reasons")) {
                reasons.add(r.asText(""));
            }
        }
        return new ScoreResult(score, pass, false, summary, reasons);
    }

    /** 预筛失败的快捷构造(不调模型) */
    public static ScoreResult preFilteredFail(String reason) {
        return new ScoreResult(0, false, false, reason, List.of(reason));
    }

    /** 待确认的快捷构造(不调模型,不判通过):如期望职能证据缺失 */
    public static ScoreResult pending(String reason) {
        return new ScoreResult(0, false, true, reason, List.of(reason));
    }
}
