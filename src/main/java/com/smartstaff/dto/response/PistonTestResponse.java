package com.smartstaff.dto.response;

import java.util.List;

/** POST /api/config/piston/test — hello-world per language. ok = every language works. */
public record PistonTestResponse(boolean ok, String piston_url, List<Language> languages) {
    public record Language(String language, boolean ok, String version, String message) {}
}
