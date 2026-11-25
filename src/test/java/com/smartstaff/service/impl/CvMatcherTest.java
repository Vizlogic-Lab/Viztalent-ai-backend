package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.ai.AiFeature;
import com.smartstaff.client.GeminiClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CvMatcherTest {

    private final ObjectMapper json = new ObjectMapper();

    private static String reply(String inner) throws Exception {
        String text = new ObjectMapper().writeValueAsString(inner);
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + text + "}]}}]}";
    }

    @Test
    void parsesScoreAndListsAndClampsScore() throws Exception {
        GeminiClient gemini = mock(GeminiClient.class);
        when(gemini.generateContent(anyString(), anyMap(), eq(AiFeature.CV_MATCH))).thenReturn(reply(
                "{\"score\":140,\"summary\":\"Strong Genesys Cloud fit.\",\"strengths\":[\"Genesys Cloud\",\"IVR\",\"a\",\"b\",\"c\"],\"gaps\":[]}"));
        CvMatcher.Result r = new CvMatcher(gemini, json).match("key", "JD", "CV");
        assertThat(r.score()).isEqualTo(100);
        assertThat(r.summary()).isEqualTo("Strong Genesys Cloud fit.");
        assertThat(r.strengths()).hasSize(4).first().isEqualTo("Genesys Cloud");
        assertThat(r.gaps()).isEmpty();
    }

    @Test
    void missingScoreIsAnError() throws Exception {
        GeminiClient gemini = mock(GeminiClient.class);
        when(gemini.generateContent(anyString(), anyMap(), any())).thenReturn(reply("{\"summary\":\"x\"}"));
        assertThatThrownBy(() -> new CvMatcher(gemini, json).match("key", "JD", "CV"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void longTextIsClipped() {
        assertThat(CvMatcher.clip("x".repeat(20_000))).hasSize(CvMatcher.MAX_CHARS);
        assertThat(CvMatcher.clip(null)).isEmpty();
    }
}
