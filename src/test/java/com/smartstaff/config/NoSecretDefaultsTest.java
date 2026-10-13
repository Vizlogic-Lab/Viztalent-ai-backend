package com.smartstaff.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** L1: no secret has a built-in default in the committed configuration. */
class NoSecretDefaultsTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+)(?::([^}]*))?}");
    /** Names that hold a secret (RATE_LIMIT_TOKEN_PER_MINUTE etc. are settings, not secrets). */
    private static final Pattern SECRET_NAME = Pattern.compile(".*(PASSWORD|SECRET|_KEY|_TOKEN)$");

    @Test
    @DisplayName("application.yml and docker-compose.yml give no secret a default value")
    void noDefaults() throws Exception {
        for (Path file : new Path[]{Path.of("src/main/resources/application.yml"), Path.of("docker-compose.yml")}) {
            Matcher m = PLACEHOLDER.matcher(Files.readString(file));
            while (m.find()) {
                String name = m.group(1), fallback = m.group(2);
                if (!SECRET_NAME.matcher(name).matches()) continue;
                // Allowed: no default, empty default (optional override), or compose's ":?error" / ":-" forms.
                boolean ok = fallback == null || fallback.isEmpty() || fallback.startsWith("?") || fallback.equals("-");
                assertThat(ok).as("%s in %s must not have a built-in default", name, file).isTrue();
            }
        }
    }

    @Test
    @DisplayName("the required secrets have no default at all")
    void requiredSecretsHaveNoDefault() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("${DB_PASSWORD}", "${APP_JWT_SECRET}", "${SETTINGS_ENCRYPTION_KEY}");
    }
}
