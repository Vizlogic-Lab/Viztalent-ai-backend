package com.smartstaff.dto.response;

import java.util.List;

public record AnswerKeyLevelResponse(String level, int count, List<AssessmentQuestionResponse> questions) {}
