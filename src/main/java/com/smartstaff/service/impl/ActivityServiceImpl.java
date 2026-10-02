package com.smartstaff.service.impl;

import com.smartstaff.dto.response.ActivityEventResponse;
import com.smartstaff.repository.ActivityEventRepository;
import com.smartstaff.service.ActivityService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** F14: the activity feed is activity_events only, written by the services
 *  (JD and resumes uploaded, screening run, assessment generated/approved,
 *  invite sent, submitted, evaluated, validated). V21 backfilled the rows the
 *  old feed used to derive from jobs, resumes and candidates. */
@Service
public class ActivityServiceImpl implements ActivityService {

    private final ActivityEventRepository activityEvents;

    public ActivityServiceImpl(ActivityEventRepository activityEvents) {
        this.activityEvents = activityEvents;
    }

    @Override
    public List<ActivityEventResponse> recentActivity(UUID jobId, int limit) {
        return activityEvents.findByJobIdOrderByAtDescIdDesc(jobId, PageRequest.of(0, Math.max(1, limit))).stream()
                .map(e -> new ActivityEventResponse(e.getKind(), e.getMessage(), e.getAt()))
                .toList();
    }
}
