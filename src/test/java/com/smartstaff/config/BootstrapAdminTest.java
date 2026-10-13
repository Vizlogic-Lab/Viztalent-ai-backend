package com.smartstaff.config;

import com.smartstaff.entity.Role;
import com.smartstaff.entity.User;
import com.smartstaff.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** L1: the first admin from BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD. */
class BootstrapAdminTest {

    private static UserRepository users(boolean adminExists) {
        UserRepository mock = Mockito.mock(UserRepository.class);
        Mockito.when(mock.existsByRole(Role.ADMIN)).thenReturn(adminExists);
        return mock;
    }

    private static PasswordEncoder encoder() {
        PasswordEncoder mock = Mockito.mock(PasswordEncoder.class);
        Mockito.when(mock.encode(Mockito.anyString())).thenReturn("hashed");
        return mock;
    }

    @Test
    @DisplayName("not configured: does nothing")
    void notConfigured() {
        UserRepository users = users(false);
        assertThat(new BootstrapAdmin(users, encoder(), "", "", "").createIfNeeded()).isEqualTo(BootstrapAdmin.Outcome.NOT_CONFIGURED);
        Mockito.verify(users, Mockito.never()).save(Mockito.any());
    }

    @Test
    @DisplayName("fresh database: creates an approved admin who must change the password")
    void createsOnFreshDatabase() {
        UserRepository users = users(false);
        var outcome = new BootstrapAdmin(users, encoder(), "owner@company.com", "Initial-Pass-2026", "Owner").createIfNeeded();
        assertThat(outcome).isEqualTo(BootstrapAdmin.Outcome.CREATED);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        Mockito.verify(users).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getEmail()).isEqualTo("owner@company.com");
        assertThat(saved.getValue().isMustChangePassword()).isTrue();
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
    }

    @Test
    @DisplayName("an admin already exists: nothing is created")
    void adminExists() {
        UserRepository users = users(true);
        assertThat(new BootstrapAdmin(users, encoder(), "owner@company.com", "Initial-Pass-2026", null).createIfNeeded())
                .isEqualTo(BootstrapAdmin.Outcome.ADMIN_EXISTS);
        Mockito.verify(users, Mockito.never()).save(Mockito.any());
    }

    @Test
    @DisplayName("half-configured, a bad email or a weak password stops the start (password never in the message)")
    void misconfigured() {
        assertThatThrownBy(() -> new BootstrapAdmin(users(false), encoder(), "owner@company.com", "", null).createIfNeeded())
                .hasMessageContaining("both");
        assertThatThrownBy(() -> new BootstrapAdmin(users(false), encoder(), "not-an-email", "Initial-Pass-2026", null).createIfNeeded())
                .hasMessageContaining("BOOTSTRAP_ADMIN_EMAIL");
        assertThatThrownBy(() -> new BootstrapAdmin(users(false), encoder(), "owner@company.com", "short1", null).createIfNeeded())
                .hasMessageContaining("BOOTSTRAP_ADMIN_PASSWORD").hasMessageNotContaining("short1");
    }
}
