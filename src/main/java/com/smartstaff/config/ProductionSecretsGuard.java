package com.smartstaff.config;

import com.smartstaff.repository.UserRepository;
import com.smartstaff.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Refuses to start under the `prod` profile (SPRING_PROFILES_ACTIVE=prod) with an
 *  insecure configuration: a missing secret, a JWT secret under 32 bytes, a value that
 *  is (or was) a known development default, or a demo account in the database.
 *  Otherwise a forgotten env var would ship a publicly known JWT signing key (anyone
 *  could mint an admin token) or encryption key for the secrets stored in Settings.
 *
 *  Missing GEMINI_API_KEY / SMTP_PASSWORD only WARN: both can be saved (encrypted) in
 *  Settings instead. Never logs a secret value. Not active in dev runs or tests. */
@Component
@Profile("prod")
public class ProductionSecretsGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ProductionSecretsGuard.class);

    static final int MIN_JWT_SECRET_BYTES = 32;
    static final int MIN_ENCRYPTION_KEY_LENGTH = 24;

    /** Demo accounts DemoDataSeeder creates under the dev profile. */
    static final List<String> DEMO_EMAILS = List.of("admin@viztalent.demo", "employee1@viztalent.demo");

    /** SHA-256 of the development defaults this project has ever shipped (application.yml
     *  and docker-compose.yml before L1), so they're recognised without being in the code. */
    static final Set<String> KNOWN_DEFAULT_HASHES = Set.of(
            "e5be90f499919151af49fafb39759ad843a58a7ddcf8fc554ebbdaff7ad01e01",   // old JWT_SECRET default
            "549cd32185fac1f9d0e673fe6a7757deb12412ba944798ec482e4c6d9f3e177d",   // old JWT_SECRET compose default
            "4c251d9a5466f3f7e06cdd6b41f263f666f9523a4a1123a3776194954d02df76",   // old APP_ENCRYPTION_KEY default
            "4773f0e98e634074bbb86a25e3d00b54efeb94096ecdfd5fe62e1dc4e10200ad"    // old POSTGRES_PASSWORD default
    );

    private final String jwtSecret;
    private final String encryptionKey;
    private final String dbPassword;
    private final UserRepository userRepository;
    private final SettingsService settings;
    private final String smtpPassword;

    public ProductionSecretsGuard(
            @Value("${app.jwt.secret:}") String jwtSecret,
            @Value("${app.encryption.key:}") String encryptionKey,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${app.smtp.password:}") String smtpPassword,
            UserRepository userRepository,
            SettingsService settings
    ) {
        this.jwtSecret = jwtSecret;
        this.encryptionKey = encryptionKey;
        this.dbPassword = dbPassword;
        this.smtpPassword = smtpPassword;
        this.userRepository = userRepository;
        this.settings = settings;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start with the 'prod' profile, insecure configuration: " + String.join("; ", problems));
        }
        warnings().forEach(log::warn);
    }

    /** Everything that blocks a prod start; empty means fine. Names only, never values. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (blank(jwtSecret)) {
            problems.add("APP_JWT_SECRET is missing");
        } else if (isKnownDefault(jwtSecret)) {
            problems.add("APP_JWT_SECRET is a known development default");
        } else if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            problems.add("APP_JWT_SECRET must be at least " + MIN_JWT_SECRET_BYTES + " bytes");
        }
        if (blank(encryptionKey)) {
            problems.add("SETTINGS_ENCRYPTION_KEY is missing");
        } else if (isKnownDefault(encryptionKey)) {
            problems.add("SETTINGS_ENCRYPTION_KEY is a known development default");
        } else if (encryptionKey.length() < MIN_ENCRYPTION_KEY_LENGTH) {
            problems.add("SETTINGS_ENCRYPTION_KEY must be at least " + MIN_ENCRYPTION_KEY_LENGTH + " characters");
        }
        if (blank(dbPassword)) {
            problems.add("DB_PASSWORD is missing");
        } else if (isKnownDefault(dbPassword)) {
            problems.add("DB_PASSWORD is a known development default");
        }
        for (String email : DEMO_EMAILS) {
            if (userRepository.existsByEmailIgnoreCase(email)) {
                problems.add("Demo account " + email + " must not exist in production (run scripts/remove-demo-data.sql)");
            }
        }
        return problems;
    }

    /** Not fatal: these can also be saved in Settings. */
    public List<String> warnings() {
        List<String> out = new ArrayList<>();
        String gemini;
        try {
            gemini = settings.getGeminiApiKeyOrNull();
        } catch (RuntimeException e) {
            gemini = null;   // e.g. a saved key that can't be decrypted with this SETTINGS_ENCRYPTION_KEY
        }
        if (blank(gemini)) {
            out.add("No Gemini API key: set GEMINI_API_KEY or save one in Settings, or AI features will be unavailable.");
        }
        if (blank(smtpPassword)) {
            out.add("SMTP_PASSWORD is not set: emails use the SMTP password saved in Settings, if any.");
        }
        return out;
    }

    static boolean isKnownDefault(String value) {
        return value.startsWith("dev-only") || KNOWN_DEFAULT_HASHES.contains(sha256(value));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
