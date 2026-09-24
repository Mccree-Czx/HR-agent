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
}
