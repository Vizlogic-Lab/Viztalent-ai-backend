package com.smartstaff.config;

import com.smartstaff.repository.UserRepository;
import com.smartstaff.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** L1: prod refuses to start with missing/weak/default secrets or demo accounts. */
class ProductionSecretsGuardTest {

    private static final String STRONG_JWT = "a-real-production-secret-of-sufficient-length-1234";
    private static final String STRONG_KEY = "a-real-encryption-key-that-is-long-enough";
    private static final String STRONG_DB = "correct-horse-battery-staple";

    private static UserRepository users(String... existingEmails) {
        UserRepository mock = Mockito.mock(UserRepository.class);
        Mockito.when(mock.existsByEmailIgnoreCase(Mockito.anyString())).thenReturn(false);
        for (String e : existingEmails) Mockito.when(mock.existsByEmailIgnoreCase(e)).thenReturn(true);
        return mock;
    }

    private static SettingsService settings(String geminiKey) {
        SettingsService mock = Mockito.mock(SettingsService.class);
        Mockito.when(mock.getGeminiApiKeyOrNull()).thenReturn(geminiKey);
        return mock;
    }

    private static ProductionSecretsGuard guard(String jwt, String key, String db, UserRepository users) {
        return new ProductionSecretsGuard(jwt, key, db, "smtp-secret", users, settings("gemini-key"));
    }

    @Test
    @DisplayName("strong, non-default secrets and no demo accounts: starts")
    void acceptsStrongSecrets() {
        var g = guard(STRONG_JWT, STRONG_KEY, STRONG_DB, users());
        assertThat(g.problems()).isEmpty();
        assertThatCode(g::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("missing secrets are named (never their values) and block the start")
    void rejectsMissing() {
        var g = guard("", null, " ", users());
        assertThat(g.problems()).containsExactlyInAnyOrder(
                "APP_JWT_SECRET is missing", "SETTINGS_ENCRYPTION_KEY is missing", "DB_PASSWORD is missing");
        assertThatThrownBy(g::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_JWT_SECRET");
    }

    @Test
    @DisplayName("the development defaults this project shipped are recognised and refused")
    void rejectsKnownDefaults() {
        // The old built-in dev values (public placeholders in the repo history, not secrets).
        var g = guard("dev-only-secret-change-me-please-32bytes-min", "dev-only-encryption-key-change-me",
                "smartstaff_classic_dev", users());
        assertThat(g.problems()).containsExactlyInAnyOrder(
                "APP_JWT_SECRET is a known development default",
                "SETTINGS_ENCRYPTION_KEY is a known development default",
                "DB_PASSWORD is a known development default");
    }

    @Test
    @DisplayName("a JWT secret under 32 bytes, or an encryption key under 24 characters, is refused")
    void rejectsShortSecrets() {
        assertThat(guard("too-short", STRONG_KEY, STRONG_DB, users()).problems())
                .singleElement().asString().contains("APP_JWT_SECRET").contains("32 bytes");
        assertThat(guard(STRONG_JWT, "short-key", STRONG_DB, users()).problems())
                .singleElement().asString().contains("SETTINGS_ENCRYPTION_KEY").contains("24");
        // 17 characters but 34 bytes (multi-byte): counted in bytes, so it passes.
        assertThat(guard("ééééééééééééééééé", STRONG_KEY, STRONG_DB, users()).problems()).isEmpty();
    }

    @Test
    @DisplayName("a demo account in the database blocks a prod start")
    void rejectsDemoAccounts() {
        var g = guard(STRONG_JWT, STRONG_KEY, STRONG_DB, users("admin@viztalent.demo"));
        assertThat(g.problems()).singleElement().asString().contains("admin@viztalent.demo").contains("remove-demo-data.sql");
    }

    @Test
    @DisplayName("no Gemini key or SMTP password only warns: both can live (encrypted) in Settings")
    void geminiAndSmtpOnlyWarn() {
        var g = new ProductionSecretsGuard(STRONG_JWT, STRONG_KEY, STRONG_DB, "", users(), settings(null));
        assertThat(g.problems()).isEmpty();
        assertThat(g.warnings()).hasSize(2).anySatisfy(w -> assertThat(w).contains("GEMINI_API_KEY"))
                .anySatisfy(w -> assertThat(w).contains("SMTP_PASSWORD"));
        assertThatCode(g::afterPropertiesSet).doesNotThrowAnyException();
    }
}
