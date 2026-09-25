package com.smartstaff.dto.response;

import java.util.List;

public record QuestionBankResponse(boolean ok, QuestionBankStatsResponse stats, List<QuestionUploadSummaryResponse> uploads) {}
