package com.smartstaff.dto.response;

import java.util.Map;

public record QuestionBankStatsResponse(int total, Map<String, Long> by_type, Map<String, Long> by_level, int uploads) {}
