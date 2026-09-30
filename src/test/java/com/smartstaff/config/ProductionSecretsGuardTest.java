package com.smartstaff.config;

import com.smartstaff.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecretsGuardTest {

    private static final String STRONG_JWT = "a-real-production-secret-of-sufficient-length-1234";
    private static final String STRONG_KEY = "a-real-encryption-key-that-is-long-enough";
    private static final String STRONG_DB = "correct-horse-battery-staple";

    private UserRepository mockUserRepository() {
        UserRepository mock = Mockito.mock(UserRepository.class);
        Mockito.when(mock.existsByRoleAndEmailIgnoreCase(Mockito.any(), Mockito.anyString())).thenReturn(false);
        return mock;
    }

    @Test
    @DisplayName("refuses to start when every secret is still a development default, and names each one")
    void rejectsDevDefaults() {
        var guard = new ProductionSecretsGuard(
                "dev-only-secret-change-me-please-32bytes-min",
                "dev-only-encryption-key-change-me",
                "smartstaff_classic_dev",
                mockUserRepository());

        assertThat(guard.problems()).hasSize(3);
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("APP_ENCRYPTION_KEY")
                .hasMessageContaining("POSTGRES_PASSWORD");
    }

    @Test
    @DisplayName("starts normally with real secrets")
    void acceptsRealSecrets() {
        var guard = new ProductionSecretsGuard(STRONG_JWT, STRONG_KEY, STRONG_DB, mockUserRepository());

        assertThat(guard.problems()).isEmpty();
        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a JWT secret that is set but too short to be safe is rejected too")
    void rejectsShortJwtSecret() {
        var guard = new ProductionSecretsGuard("too-short", STRONG_KEY, STRONG_DB, mockUserRepository());

        assertThat(guard.problems()).singleElement().asString().contains("JWT_SECRET").contains("32");
    }

    @Test
    @DisplayName("an encryption key that is set but too short is rejected too")
    void rejectsShortEncryptionKey() {
        var guard = new ProductionSecretsGuard(STRONG_JWT, "short-key", STRONG_DB, mockUserRepository());

        assertThat(guard.problems()).singleElement().asString().contains("APP_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("blank or missing secrets count as unset")
    void blankIsUnset() {
        var guard = new ProductionSecretsGuard("", null, STRONG_DB, mockUserRepository());

        assertThat(guard.problems()).hasSize(2);
    }

    @Test
    @DisplayName("refuses to start if demo admin exists in prod")
    void rejectsDemoAdminInProd() {
        UserRepository mockUsers = Mockito.mock(UserRepository.class);
        Mockito.when(mockUsers.existsByRoleAndEmailIgnoreCase(Mockito.any(), Mockito.eq("admin@viztalent.demo")))
                .thenReturn(true);

        var guard = new ProductionSecretsGuard(STRONG_JWT, STRONG_KEY, STRONG_DB, mockUsers);

        assertThat(guard.problems()).anySatisfy(problem ->
                assertThat(problem).contains("admin@viztalent.demo"));
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin@viztalent.demo");
    }
}
