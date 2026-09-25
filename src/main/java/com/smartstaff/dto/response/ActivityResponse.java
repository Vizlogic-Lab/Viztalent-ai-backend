package com.smartstaff.dto.response;

import java.util.List;

public record ActivityResponse(boolean ok, List<ActivityEventResponse> activity) {}
