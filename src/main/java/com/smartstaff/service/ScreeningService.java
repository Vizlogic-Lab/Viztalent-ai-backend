package com.smartstaff.service;

import com.smartstaff.dto.response.ProgressResponse;
import com.smartstaff.dto.response.RunScreeningResponse;
import com.smartstaff.entity.User;

import java.util.UUID;

public interface ScreeningService {

    RunScreeningResponse runScreening(UUID jobId);

    ProgressResponse progress();

    /** CSV export of every candidate the requester can see (admin: all,
     *  employee: only their own jobs') — see JobService.listJobs for the
     *  same scoping rule. */
    byte[] downloadReportCsv(User requester);
}
