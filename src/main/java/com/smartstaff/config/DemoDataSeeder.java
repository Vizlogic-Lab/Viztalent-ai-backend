package com.smartstaff.config;

import com.smartstaff.entity.AccountStatus;
import com.smartstaff.entity.Role;
import com.smartstaff.entity.User;
import com.smartstaff.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Seeds the two demo accounts the login screen's "Demo Credentials" buttons
 *  already advertise, so a fresh database is usable immediately. Idempotent —
 *  only inserts what's missing. */
@Component
public class DemoDataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DemoDataSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (!userRepository.existsByRoleAndEmailIgnoreCase(Role.ADMIN, "admin@viztalent.demo")) {
            userRepository.save(new User(Role.ADMIN, "Demo Admin", "admin@viztalent.demo", null,
                    passwordEncoder.encode("AdminDemo@123"), AccountStatus.APPROVED));
        }
        if (!userRepository.existsByRoleAndEmployeeIdIgnoreCase(Role.USER, "EMP1001")) {
            userRepository.save(new User(Role.USER, "Demo Employee", "employee1@viztalent.demo", "EMP1001",
                    passwordEncoder.encode("EmployeeDemo@123"), AccountStatus.APPROVED));
        }
    }
}
