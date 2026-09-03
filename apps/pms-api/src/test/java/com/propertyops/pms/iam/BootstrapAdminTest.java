package com.propertyops.pms.iam;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.propertyops.pms.common.api.BusinessException;

class BootstrapAdminTest {
    @Test
    void rejectsWeakBootstrapPasswordBeforeCreatingANewAccount() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var passwordEncoder = mock(PasswordEncoder.class);
        when(jdbc.queryForList(argThat(sql -> sql != null && sql.contains("FROM sys_user")), anyMap(), eq(String.class)))
                .thenReturn(List.of());
        when(jdbc.queryForList(argThat(sql -> sql != null && sql.contains("FROM community")), anyMap(), eq(String.class)))
                .thenReturn(List.of("existing-project-id"));
        var bootstrapAdmin = new BootstrapAdmin(
                jdbc,
                passwordEncoder,
                new PasswordPolicy(),
                "admin",
                "weak-password");

        assertThatThrownBy(() -> bootstrapAdmin.run(null))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("WEAK_PASSWORD");

        verifyNoInteractions(passwordEncoder);
        verify(jdbc).queryForList(contains("FROM sys_user"), anyMap(), eq(String.class));
        verify(jdbc).queryForList(contains("FROM community"), anyMap(), eq(String.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void defersBootstrapAccountUntilTheWebSetupCreatesAProject() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var passwordEncoder = mock(PasswordEncoder.class);
        when(jdbc.queryForList(argThat(sql -> sql != null && sql.contains("FROM sys_user")), anyMap(), eq(String.class)))
                .thenReturn(List.of());
        when(jdbc.queryForList(argThat(sql -> sql != null && sql.contains("FROM community")), anyMap(), eq(String.class)))
                .thenReturn(List.of());
        var bootstrapAdmin = new BootstrapAdmin(
                jdbc,
                passwordEncoder,
                new PasswordPolicy(),
                "admin",
                "Strong-Bootstrap-2026!");

        assertThatCode(() -> bootstrapAdmin.run(null)).doesNotThrowAnyException();

        verifyNoInteractions(passwordEncoder);
        verify(jdbc, never()).update(contains("INSERT INTO sys_user ("), anyMap());
    }

    @Test
    void doesNotRejectAnExistingAccountWhenBootstrapSecretChanges() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var passwordEncoder = mock(PasswordEncoder.class);
        when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of("existing-user-id"));
        var bootstrapAdmin = new BootstrapAdmin(
                jdbc,
                passwordEncoder,
                new PasswordPolicy(),
                "admin",
                "weak-password");

        assertThatCode(() -> bootstrapAdmin.run(null)).doesNotThrowAnyException();

        verifyNoInteractions(passwordEncoder);
        verify(jdbc, never()).update(contains("INSERT INTO sys_user ("), anyMap());
    }
}
