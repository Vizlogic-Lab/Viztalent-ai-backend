package com.smartstaff.service.impl;

import com.smartstaff.entity.ActivityEvent;
import com.smartstaff.repository.ActivityEventRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** F14: writes activity_events in the caller's transaction, so an event exists
 *  exactly when the change it describes was committed. Repository-only, so any
 *  service can depend on it without a bean cycle. */
@Component
public class ActivityRecorder {

    public static final String JD = "jd";
    public static final String RESUMES = "resumes";
    public static final String SCREENING = "screening";
    public static final String ASSESSMENT = "assessment";
    public static final String INVITE = "invite";
    public static final String SUBMISSION = "submission";
    public static final String EVALUATION = "evaluation";
    public static final String VALIDATION = "validation";

    private final ActivityEventRepository events;

    public ActivityRecorder(ActivityEventRepository events) {
        this.events = events;
    }

    public void record(UUID jobId, String kind, String message, UUID subjectId) {
        events.save(new ActivityEvent(jobId, kind, message, subjectId));
    }

    /** Candidate name, else email. */
    public static String who(String name, String email) {
        return name != null && !name.isBlank() ? name : email;
    }
}
