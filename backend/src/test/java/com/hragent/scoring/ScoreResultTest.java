package com.hragent.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parseValidJson() throws Exception {
        var node = objectMapper.readTree(
                "{\"score\":75,\"pass\":true,\"summary\":\"匹配良好\",\"reasons\":[\"技能吻合\",\"薪资合适\"]}");
        ScoreResult result = ScoreResult.fromJson(node, 60);
        assertEquals(75, result.score());
        assertTrue(result.pass());
        assertEquals("匹配良好", result.summary());
        assertEquals(2, result.reasons().size());
    }

    @Test
    void rejectMissingScore() throws Exception {
        var node = objectMapper.readTree("{\"pass\":true,\"summary\":\"x\",\"reasons\":[]}");
        assertThrows(IllegalArgumentException.class, () -> ScoreResult.fromJson(node, 60));
    }

    @Test
    void rejectScoreOutOfRange() throws Exception {
        var node = objectMapper.readTree("{\"score\":150,\"pass\":true,\"summary\":\"x\",\"reasons\":[]}");
        assertThrows(IllegalArgumentException.class, () -> ScoreResult.fromJson(node, 60));
    }

    @Test
    void rejectMissingPass() throws Exception {
        var node = objectMapper.readTree("{\"score\":80,\"summary\":\"x\",\"reasons\":[]}");
        assertThrows(IllegalArgumentException.class, () -> ScoreResult.fromJson(node, 60));
    }

    @Test
    void rejectContradictoryPass() throws Exception {
        var node = objectMapper.readTree("{\"score\":85,\"pass\":false,\"summary\":\"x\",\"reasons\":[]}");
        assertThrows(IllegalArgumentException.class, () -> ScoreResult.fromJson(node, 60));
    }

    @Test
    void allowLowScoreFail() throws Exception {
        var node = objectMapper.readTree("{\"score\":35,\"pass\":false,\"summary\":\"不匹配\",\"reasons\":[\"薪资不符\"]}");
        ScoreResult result = ScoreResult.fromJson(node, 60);
        assertEquals(35, result.score());
        assertFalse(result.pass());
    }

    /** 硬规则:模型判过但分数低于门槛 → 降级为不通过(不得绕过岗位门槛外发) */
    @Test
    void modelPassBelowThresholdDowngradesToFail() throws Exception {
        var node = objectMapper.readTree("{\"score\":70,\"pass\":true,\"summary\":\"模型认为可过\",\"reasons\":[]}");
        ScoreResult result = ScoreResult.fromJson(node, 80);
        assertEquals(70, result.score());
        assertFalse(result.pass(), "score 未达门槛 80,即使模型 pass=true 也必须 FAIL");
        assertFalse(result.pending());
    }

    /** 达门槛且模型判过 → PASS */
    @Test
    void modelPassAtThresholdIsPass() throws Exception {
        var node = objectMapper.readTree("{\"score\":80,\"pass\":true,\"summary\":\"达门槛\",\"reasons\":[]}");
        ScoreResult result = ScoreResult.fromJson(node, 80);
        assertTrue(result.pass());
        assertEquals(80, result.score());
    }

    /** 门槛参数缺省时沿用向后兼容默认值 60 */
    @Test
    void defaultOverloadUsesCompatibleThreshold() throws Exception {
        assertTrue(ScoreResult.fromJson(
                objectMapper.readTree("{\"score\":70,\"pass\":true,\"summary\":\"x\",\"reasons\":[]}")).pass());
        assertFalse(ScoreResult.fromJson(
                objectMapper.readTree("{\"score\":50,\"pass\":true,\"summary\":\"x\",\"reasons\":[]}")).pass());
    }
}
