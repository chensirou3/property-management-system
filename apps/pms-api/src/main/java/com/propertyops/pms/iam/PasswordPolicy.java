package com.propertyops.pms.iam;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.propertyops.pms.common.api.BusinessException;

@Component
public class PasswordPolicy {
    public void validate(String username, String password) {
        if (password == null || password.length() < 12 || password.length() > 200) {
            weak("密码长度必须为 12–200 个字符");
        }
        boolean upper = password.chars().anyMatch(Character::isUpperCase);
        boolean lower = password.chars().anyMatch(Character::isLowerCase);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        boolean symbol = password.chars().anyMatch(value -> !Character.isLetterOrDigit(value));
        if (!(upper && lower && digit && symbol)) {
            weak("密码必须同时包含大写字母、小写字母、数字和符号");
        }
        String normalizedUsername = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (normalizedUsername.length() >= 3
                && password.toLowerCase(Locale.ROOT).contains(normalizedUsername)) {
            weak("密码不能包含完整登录账号");
        }
    }

    private void weak(String message) {
        throw new BusinessException("WEAK_PASSWORD", message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
