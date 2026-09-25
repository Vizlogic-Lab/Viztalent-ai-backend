package com.smartstaff.repository;

import com.smartstaff.entity.Role;
import com.smartstaff.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByRoleAndEmailIgnoreCase(Role role, String email);

    Optional<User> findByRoleAndEmployeeIdIgnoreCase(Role role, String employeeId);

    List<User> findByRoleOrderByNameAsc(Role role);

    boolean existsByRoleAndEmailIgnoreCase(Role role, String email);

    boolean existsByRoleAndEmployeeIdIgnoreCase(Role role, String employeeId);
}
