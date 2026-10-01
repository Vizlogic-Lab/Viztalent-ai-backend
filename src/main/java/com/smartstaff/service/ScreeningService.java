package com.smartstaff.service;

import com.smartstaff.dto.response.ProgressResponse;
import com.smartstaff.dto.response.RunScreeningResponse;

import java.util.UUID;

public interface ScreeningService {

    RunScreeningResponse runScreening(UUID jobId);

    ProgressResponse progress();
}
