package com.smartstaff.dto.response;

import java.util.List;

public record AnswerKeyResponse(boolean ok, String role_title, List<AnswerKeyLevelResponse> levels) {}
