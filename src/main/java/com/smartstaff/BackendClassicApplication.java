package com.smartstaff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// UserDetailsServiceAutoConfiguration is excluded because auth is entirely
// custom (JWT + our own User entity) — without this, Spring Security still
// stands up a throwaway in-memory user and prints a generated password on
// every boot, which is just noise here.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class BackendClassicApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendClassicApplication.class, args);
    }
}
