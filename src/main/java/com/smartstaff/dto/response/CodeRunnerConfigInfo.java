package com.smartstaff.dto.response;

import java.util.List;

public record CodeRunnerConfigInfo(String piston_url, boolean piston_enabled, List<String> languages) {}
