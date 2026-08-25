package com.propertyops.pms.iam;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.propertyops.pms.common.api.BusinessException;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsLongMixedCategoryPassword() {
        assertThatCode(() -> policy.validate("project.manager", "Safe-Access-2026!"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingCharacterCategories() {
        assertThatThrownBy(() -> policy.validate("project.manager", "all-lowercase-password"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("WEAK_PASSWORD");
    }

    @Test
    void rejectsPasswordContainingLoginAccount() {
        assertThatThrownBy(() -> policy.validate("project.manager", "Project.Manager-2026!"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("WEAK_PASSWORD");
    }
}
