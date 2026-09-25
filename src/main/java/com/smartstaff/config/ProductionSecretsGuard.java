package com.smartstaff.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Refuses to start under the `prod` profile (SPRING_PROFILES_ACTIVE=prod)
 *  while any secret is still one of the development defaults baked into
 *  application.yml — otherwise a forgotten env var would quietly ship a
 *  publicly-known JWT signing key (anyone could mint an admin token) and a
 *  publicly-known encryption key for the stored Gemini/Twilio secrets.
 *
 *  Not active in local/dev runs (no profile) or in tests. */
@Component
@Profile("prod")
public class ProductionSecretsGuard implements InitializingBean {

    private static final int MIN_JWT_SECRET_LENGTH = 32;
    private static final int MIN_ENCRYPTION_KEY_LENGTH = 24;
    private static final String DEV_DB_PASSWORD = "smartstaff_classic_dev";

    private final String jwtSecret;
    private final String encryptionKey;
    private final String dbPassword;

    public ProductionSecretsGuard(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.encryption.key}") String encryptionKey,
            @Value("${spring.datasource.password}") String dbPassword
    ) {
        this.jwtSecret = jwtSecret;
        this.encryptionKey = encryptionKey;
        this.dbPassword = dbPassword;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start with the 'prod' profile — insecure configuration: "
                            + String.join("; ", problems));
        }
    }

    /** Everything wrong with the current secrets; empty means fine. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (isDevDefault(jwtSecret)) {
            problems.add("JWT_SECRET is unset (still the development default)");
        } else if (jwtSecret.length() < MIN_JWT_SECRET_LENGTH) {
            problems.add("JWT_SECRET must be at least " + MIN_JWT_SECRET_LENGTH + " characters");
        }
        if (isDevDefault(encryptionKey)) {
            problems.add("APP_ENCRYPTION_KEY is unset (still the development default)");
        } else if (encryptionKey.length() < MIN_ENCRYPTION_KEY_LENGTH) {
            problems.add("APP_ENCRYPTION_KEY must be at least " + MIN_ENCRYPTION_KEY_LENGTH + " characters");
        }
        if (DEV_DB_PASSWORD.equals(dbPassword)) {
            problems.add("POSTGRES_PASSWORD is unset (still the development default)");
        }
        return problems;
    }

    private static boolean isDevDefault(String value) {
        return value == null || value.isBlank() || value.startsWith("dev-only");
    }
}
