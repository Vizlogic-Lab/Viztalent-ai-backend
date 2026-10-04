package com.smartstaff.dto.response;

import com.smartstaff.entity.InterviewTurn;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** One interview turn for HR (transcripts, call status). F16 adds the grading:
 *  score / max_score are the engine's (0/1/2 per expected point), sme_score the
 *  SME's when adjusted, evidence the quoted criteria. Never sent to candidates. */
public record TranscriptTurnResponse(
        int seq,
        String category,
        String skill,
        String question,
        String answer,
        String source,
        String dimension,
        List<String> expected_points,
        BigDecimal score,
        BigDecimal max_score,
        BigDecimal sme_score,
        String sme_reason,
        List<Map<String, Object>> evidence,
        List<String> flags,
        BigDecimal speech_confidence
) {
    public static TranscriptTurnResponse of(InterviewTurn t) {
        return new TranscriptTurnResponse(t.getSeq(), t.getCategory(), t.getSkill(), t.getQuestion(), t.getAnswer(),
                t.getSource(), t.getDimension() == null ? null : t.getDimension().name(), t.getExpectedPoints(),
                t.getScore(), t.getMaxScore(), t.getSmeScore(), t.getSmeReason(), t.getEvidence(), t.getFlags(),
                t.getSpeechConfidence());
    }
}
