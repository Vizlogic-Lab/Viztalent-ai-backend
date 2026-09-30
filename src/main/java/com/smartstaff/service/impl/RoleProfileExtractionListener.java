package com.smartstaff.service.impl;

import com.smartstaff.service.JdUploadedEvent;
import com.smartstaff.service.RoleProfileService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Starts role-profile extraction once the JD upload has committed, so the
 *  Gemini call never runs inside the upload transaction. */
@Component
public class RoleProfileExtractionListener {

    private final RoleProfileService roleProfileService;

    public RoleProfileExtractionListener(RoleProfileService roleProfileService) {
        this.roleProfileService = roleProfileService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJdUploaded(JdUploadedEvent event) {
        roleProfileService.extractRoleProfile(event.jobId());
    }
}
