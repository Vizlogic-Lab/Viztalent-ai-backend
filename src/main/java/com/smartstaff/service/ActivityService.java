package com.smartstaff.service;

import com.smartstaff.dto.response.ActivityEventResponse;

import java.util.List;
import java.util.UUID;

public interface ActivityService {

    /** Newest-first, derived from Job/Resume/Candidate timestamps (see
     *  ActivityEventResponse). */
    List<ActivityEventResponse> recentActivity(UUID jobId, int limit);
}
