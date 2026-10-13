package com.smartstaff.config;

import com.smartstaff.entity.AccountStatus;
import com.smartstaff.entity.Role;
import com.smartstaff.entity.User;
import com.smartstaff.repository.UserRepository;
import com.smartstaff.util.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** First admin for a fresh database, from BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD.
 *  Runs at every start in every profile but only acts when both are set and no admin
 *  exists yet; the admin must change the password at first login (JwtAuthFilter blocks
 *  everything else until then). The password is never logged. Remove both env vars
 *  after the first start. */
@Component
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;
    private final String name;

    public BootstrapAdmin(UserRepository users, PasswordEncoder passwordEncoder,
                          @Value("${app.bootstrap-admin.email:}") String email,
                          @Value("${app.bootstrap-admin.password:}") String password,
                          @Value("${app.bootstrap-admin.name:Administrator}") String name) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.email = email == null ? "" : email.strip();
        this.password = password == null ? "" : password;
        this.name = name == null || name.isBlank() ? "Administrator" : name.strip();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createIfNeeded();
    }

    /** What happened, for tests and the log. */
    public enum Outcome { NOT_CONFIGURED, ADMIN_EXISTS, CREATED }

    @Transactional
    public Outcome createIfNeeded() {
        if (email.isEmpty() && password.isEmpty()) return Outcome.NOT_CONFIGURED;
        if (email.isEmpty() || password.isEmpty()) {
            throw new IllegalStateException("Set both BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD, or neither.");
        }
        if (users.existsByRole(Role.ADMIN)) {
            log.warn("BOOTSTRAP_ADMIN_* is set but an admin already exists; nothing created. Remove the BOOTSTRAP_ADMIN_* variables.");
            return Outcome.ADMIN_EXISTS;
        }
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_EMAIL is not a valid email address.");
        }
        String problem = PasswordPolicy.problem(password);
        if (problem != null) throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD: " + problem);

        User admin = new User(Role.ADMIN, name, email, null, passwordEncoder.encode(password), AccountStatus.APPROVED);
        admin.setMustChangePassword(true);
        users.save(admin);
        log.info("Bootstrap admin created for {}; they must change the password at first login. "
                + "Remove BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD now.", email);
        return Outcome.CREATED;
    }
}
