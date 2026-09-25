package com.hragent.scoring;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 期望职能三态判定(设计 4.2):MATCH / MISMATCH / UNKNOWN。
 * 口径与 CLI {@code tools/liepin-cli/src/toolset/resume.ts} 的 matchJobExpectations 完全一致:
 * - 仅用「明确同义映射」判定,不按"工程师"、行业或关键词包含关系泛化;
 * - 多个期望任一与目标同职能即 MATCH;
 * - 明确分类证据冲突/缺失、期望缺失或格式异常 → UNKNOWN;
 * - 绝不用候选人当前职位充当期望。
 *
 * fail-closed 语义:只有 MATCH 允许继续评分;UNKNOWN 绝不判为通过;MISMATCH 直接排除。
 */
public final class JobMatchEvaluator {

    public enum Status {
        MATCH, MISMATCH, UNKNOWN
    }

    public record Result(Status status, String reason) {

        /** 仅 MATCH 允许继续进入 AI 评分 */
        public boolean canContinueScoring() {
            return status == Status.MATCH;
        }
    }

    private static final String EXPECTATION_SOURCE = "resumeDetailVo.jobWant.jobTitleNames";

    /** 明确支持的职能族(与 CLI 一致) */
    private static final Set<String> KNOWN_FAMILIES = Set.of("hardware", "hr", "software");

    /** 明确同义映射,与 CLI TITLE_FAMILIES 完全一致;不泛化 */
    private static final Map<String, String> TITLE_FAMILIES = Map.ofEntries(
            Map.entry("硬件工程师", "hardware"),
            Map.entry("硬件研发工程师", "hardware"),
            Map.entry("高级硬件工程师", "hardware"),
            Map.entry("高级硬件研发工程师", "hardware"),
            Map.entry("人力资源总监", "hr"),
            Map.entry("HR总监", "hr"),
            Map.entry("招聘经理", "hr"),
            Map.entry("软件工程师", "software"));

    private JobMatchEvaluator() {
    }

    /** 无人工分类证据的判定(仅用明确同义映射) */
    public static Result evaluate(List<String> expectations, String targetJobTitle) {
        return evaluate(expectations, Map.of(), targetJobTitle);
    }

    /**
     * 三态职能判定。
     *
     * @param expectations           期望职能标题(来源 candidate.snapshot 的期望字段,绝不用当前职位代替)
     * @param classificationEvidence 可选的已核实归一化分类:职位标题 → 职能族(hardware/hr/software);
     *                               为空表示无人工证据,仅用明确同义映射;含未知族或与明确映射冲突 → UNKNOWN
     * @param targetJobTitle         目标岗位名称
     */
    public static Result evaluate(List<String> expectations, Map<String, String> classificationEvidence,
                                  String targetJobTitle) {
        if (expectations == null || expectations.isEmpty()) {
            return unknown("缺少可靠的求职期望或字段格式异常");
        }
        Classification wanted = classify(targetJobTitle, familyOf(classificationEvidence, targetJobTitle));
        List<Classification> entries = new ArrayList<>();
        for (String title : expectations) {
            entries.add(classify(title, familyOf(classificationEvidence, title)));
        }
        if (wanted.conflict() || entries.stream().anyMatch(Classification::conflict)) {
            return unknown("职能分类证据缺失或与明确职位映射冲突");
        }
        if (wanted.family() == null) {
            return unknown("目标岗位职能尚无可靠映射");
        }
        if (entries.stream().anyMatch(e -> wanted.family().equals(e.family()))) {
            return new Result(Status.MATCH, "至少一个明确期望方向同职能；级别、管理职责及岗位门槛仍需评分");
        }
        if (entries.stream().anyMatch(e -> e.family() == null)) {
            return unknown("存在无法确定职能的期望方向");
        }
        return new Result(Status.MISMATCH, "所有明确期望方向均与目标岗位不同职能");
    }

    /** 从候选人快照提取期望并判定;缺期望/字段异常 → UNKNOWN */
    public static Result evaluateFromSnapshot(JsonNode snapshot, String targetJobTitle) {
        Evidence evidence = extractEvidence(snapshot);
        if (evidence.malformed()) {
            return unknown("缺少可靠的求职期望或字段格式异常");
        }
        return evaluate(evidence.titles(), evidence.reviewedFamilies(), targetJobTitle);
    }

    /** 从候选人快照提取期望职能标题(仅取期望字段,绝不回退当前职位) */
    public static List<String> extractExpectations(JsonNode snapshot) {
        return extractEvidence(snapshot).titles();
    }

    record Evidence(List<String> titles, Map<String, String> reviewedFamilies, boolean malformed) {
    }

    static Evidence extractEvidence(JsonNode snapshot) {
        List<String> titles = new ArrayList<>();
        Map<String, String> reviewedFamilies = new LinkedHashMap<>();
        boolean malformed = false;
        if (snapshot != null) {
            JsonNode evidence = snapshot.get("expectation_evidence");
            if (evidence != null && evidence.isObject()) {
                JsonNode source = evidence.get("source");
                if (source != null && source.isTextual() && !EXPECTATION_SOURCE.equals(source.asText())) {
                    // 来源不可信 → 视为字段异常,拒绝匹配
                    return new Evidence(List.of(), Map.of(), true);
                }
                if (evidence.path("malformed").asBoolean(false)) {
                    malformed = true;
                }
                JsonNode entries = evidence.get("entries");
                if (entries != null) {
                    if (!entries.isArray()) {
                        malformed = true;
                    } else {
                        for (JsonNode entry : entries) {
                            JsonNode titleNode = entry.path("title");
                            if (!titleNode.isTextual() || titleNode.asText().isBlank()) {
                                malformed = true;
                                continue;
                            }
                            String title = titleNode.asText().trim();
                            titles.add(title);
                            JsonNode family = entry.get("reviewedFamily");
                            if (family != null && family.isTextual() && !family.asText().isBlank()) {
                                reviewedFamilies.put(title, family.asText().trim());
                            }
                        }
                    }
                }
            }
            if (titles.isEmpty() && !malformed) {
                // 回退:扁平期望字段 want_title(由 resume 详情命令展开)
                JsonNode wantTitle = snapshot.path("want_title");
                if (wantTitle.isTextual() && !wantTitle.asText().isBlank()) {
                    for (String part : wantTitle.asText().split("[、,，/]")) {
                        String t = part.trim();
                        if (!t.isBlank()) {
                            titles.add(t);
                        }
                    }
                }
            }
        }
        return new Evidence(titles, reviewedFamilies, malformed);
    }

    private static String familyOf(Map<String, String> evidence, String title) {
        if (evidence == null) {
            return null;
        }
        return evidence.get(title == null ? "" : title.trim());
    }

    private static Classification classify(String title, String reviewedFamily) {
        String key = title == null ? "" : title.trim();
        String alias = TITLE_FAMILIES.get(key);
        boolean verified = reviewedFamily != null && !reviewedFamily.isBlank();
        if (verified && !KNOWN_FAMILIES.contains(reviewedFamily)) {
            // 已核实分类但族不在已知集合 → 证据不可信
            return new Classification(null, true);
        }
        String family = verified ? reviewedFamily : alias;
        boolean conflict = verified && alias != null && !reviewedFamily.equals(alias);
        return new Classification(family, conflict);
    }

    private static Result unknown(String reason) {
        return new Result(Status.UNKNOWN, reason);
    }

    private record Classification(String family, boolean conflict) {
    }
}
