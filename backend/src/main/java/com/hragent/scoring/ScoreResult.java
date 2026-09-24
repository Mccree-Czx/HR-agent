package com.hragent.scoring;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 评分结果(评审 P2-11:结构化输出,字段级校验在 fromJson 中完成)。
 */
public record ScoreResult(
        int score,
        boolean pass,
        String summary,
        List<String> reasons) {

    /**
     * 从模型输出 JSON 解析并校验。
     * 非法结构/越界分数/缺失字段均抛出 IllegalArgumentException,由调用方决定重试或放弃。
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
        boolean pass = passNode.asBoolean();
        if (score >= passThreshold && !pass) {
            throw new IllegalArgumentException("score 达阈值但 pass=false,输出矛盾");
        }
        String summary = node.has("summary") ? node.get("summary").asText("") : "";
        List<String> reasons = new ArrayList<>();
        if (node.has("reasons") && node.get("reasons").isArray()) {
            for (JsonNode r : node.get("reasons")) {
                reasons.add(r.asText(""));
            }
        }
        return new ScoreResult(score, pass, summary, reasons);
    }

    /** 预筛失败的快捷构造(不调模型) */
    public static ScoreResult preFilteredFail(String reason) {
        return new ScoreResult(0, false, reason, List.of(reason));
    }
}
