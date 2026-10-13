package com.smartstaff.util;

/** The account password rule (signup, change password, bootstrap admin). */
public final class PasswordPolicy {

    private PasswordPolicy() {}

    public static final int MIN_LENGTH = 10;

    /** Null when the password is acceptable, else what's wrong (never echoes the password). */
    public static String problem(String password) {
        if (password == null || password.length() < MIN_LENGTH) return "Password must be at least " + MIN_LENGTH + " characters.";
        if (!password.matches(".*[a-zA-Z].*")) return "Password must contain at least one letter.";
        if (!password.matches(".*\\d.*")) return "Password must contain at least one digit.";
        return null;
    }
}
