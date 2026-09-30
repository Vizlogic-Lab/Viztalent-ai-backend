package com.smartstaff.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Progress of an assessment generation, stored as assessments.progress. */
public record GenerationProgress(
        int slotsTotal,
        int slotsDone,
        Map<String, Integer> filled,
        List<UnfilledSlot> unfilled,
        List<String> errors
) {
    public record UnfilledSlot(String level, int seq, QuestionType type, String skill, String reason) {}

    public static GenerationProgress start(int slotsTotal) {
        return new GenerationProgress(slotsTotal, 0, new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>());
    }

    public GenerationProgress withFilled(String level) {
        Map<String, Integer> f = new LinkedHashMap<>(filled == null ? Map.of() : filled);
        f.merge(level, 1, Integer::sum);
        return new GenerationProgress(slotsTotal, slotsDone + 1, f, unfilled, errors);
    }

    public GenerationProgress withUnfilled(UnfilledSlot slot) {
        List<UnfilledSlot> u = new ArrayList<>(unfilled == null ? List.of() : unfilled);
        u.add(slot);
        return new GenerationProgress(slotsTotal, slotsDone + 1, filled, u, errors);
    }

    public GenerationProgress withError(String error) {
        List<String> e = new ArrayList<>(errors == null ? List.of() : errors);
        if (!e.contains(error)) e.add(error);
        return new GenerationProgress(slotsTotal, slotsDone, filled, unfilled, e);
    }

    public int filledCount() {
        return filled == null ? 0 : filled.values().stream().mapToInt(Integer::intValue).sum();
    }
}
