package com.smartstaff.security;

import com.smartstaff.entity.AccountStatus;
import com.smartstaff.entity.Role;
import com.smartstaff.entity.User;
import com.smartstaff.repository.InterviewRepository;
import com.smartstaff.repository.JobRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobAccessGuardTest {

    private final JobRepository jobs = mock(JobRepository.class);
    private final InterviewRepository interviews = mock(InterviewRepository.class);
    private final JobAccessGuard guard = new JobAccessGuard(jobs, interviews);

    private final UUID jobId = UUID.randomUUID();
    private final UUID interviewId = UUID.randomUUID();

    private static Authentication as(User user) {
        return new UsernamePasswordAuthenticationToken(user, null, List.of());
    }

    private static User employee(String employeeId) {
        return new User(Role.USER, "Emp", employeeId.toLowerCase() + "@x.test", employeeId, "hash", AccountStatus.APPROVED);
    }

    private static User admin() {
        return new User(Role.ADMIN, "Admin", "admin@x.test", null, "hash", AccountStatus.APPROVED);
    }

    private void jobOwnedBy(String ownerId) {
        when(jobs.findOwnerIdById(jobId)).thenReturn(Optional.of(ownerId));
    }

    @Test
    @DisplayName("an admin can act on any job")
    void adminAllowed() {
        jobOwnedBy("EMP7");
        assertThat(guard.canAccessJob(jobId, as(admin()))).isTrue();
    }

    @Test
    @DisplayName("an employee can act on their own job (id comparison is case-insensitive)")
    void ownerAllowed() {
        jobOwnedBy("emp7");
        assertThat(guard.canAccessJob(jobId, as(employee("EMP7")))).isTrue();
    }

    @Test
    @DisplayName("an employee cannot act on someone else's job")
    void otherEmployeeDenied() {
        jobOwnedBy("EMP7");
        assertThat(guard.canAccessJob(jobId, as(employee("EMP8")))).isFalse();
    }

    @Test
    @DisplayName("an employee cannot act on an admin's job")
    void employeeDeniedOnAdminJob() {
        jobOwnedBy("admin@x.test");
        assertThat(guard.canAccessJob(jobId, as(employee("EMP7")))).isFalse();
    }

    @Test
    @DisplayName("a job that doesn't exist is let through, so the service can give its own 'not found' answer")
    void unknownJobPassesThrough() {
        when(jobs.findOwnerIdById(jobId)).thenReturn(Optional.empty());
        assertThat(guard.canAccessJob(jobId, as(employee("EMP7")))).isTrue();
    }

    @Test
    @DisplayName("an existing job is denied to a missing or non-User authentication")
    void unauthenticatedDenied() {
        jobOwnedBy("EMP7");
        assertThat(guard.canAccessJob(jobId, null)).isFalse();
        assertThat(guard.canAccessJob(jobId, new UsernamePasswordAuthenticationToken("just-a-string", null, List.of()))).isFalse();
    }

    @Test
    @DisplayName("session ids that aren't UUIDs (the frontend's 'local_react_user' placeholder, blanks, null) touch no job, so they pass")
    void nonUuidSessionsPass() {
        assertThat(guard.canAccessSession("local_react_user", as(employee("EMP7")))).isTrue();
        assertThat(guard.canAccessSession("", as(employee("EMP7")))).isTrue();
        assertThat(guard.canAccessSession(null, as(employee("EMP7")))).isTrue();
    }

    @Test
    @DisplayName("a UUID session id is checked against the job's owner")
    void uuidSessionChecked() {
        jobOwnedBy("EMP7");
        assertThat(guard.canAccessSession(jobId.toString(), as(employee("EMP7")))).isTrue();
        assertThat(guard.canAccessSession(jobId.toString(), as(employee("EMP8")))).isFalse();
        assertThat(guard.canAccessSession("  " + jobId + "  ", as(employee("EMP8")))).isFalse();
    }

    @Test
    @DisplayName("interview access follows the owner of the interview's job")
    void interviewAccess() {
        when(interviews.findJobOwnerIdById(interviewId)).thenReturn(Optional.of("EMP7"));

        assertThat(guard.canAccessInterview(interviewId, as(employee("EMP7")))).isTrue();
        assertThat(guard.canAccessInterview(interviewId, as(employee("EMP8")))).isFalse();
        assertThat(guard.canAccessInterview(interviewId, as(admin()))).isTrue();
        assertThat(guard.canAccessInterviewId(interviewId.toString(), as(employee("EMP8")))).isFalse();
    }

    @Test
    @DisplayName("an unknown or malformed interview id passes through for the service to reject")
    void unknownInterviewPasses() {
        when(interviews.findJobOwnerIdById(interviewId)).thenReturn(Optional.empty());

        assertThat(guard.canAccessInterview(interviewId, as(employee("EMP7")))).isTrue();
        assertThat(guard.canAccessInterviewId("not-a-uuid", as(employee("EMP7")))).isTrue();
    }
}
