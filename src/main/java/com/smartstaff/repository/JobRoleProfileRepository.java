package com.smartstaff.repository;

import com.smartstaff.entity.JobRoleProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface JobRoleProfileRepository extends JpaRepository<JobRoleProfile, UUID> {
}
