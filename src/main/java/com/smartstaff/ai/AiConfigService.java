package com.smartstaff.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.entity.AppSetting;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.AppSettingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/** Which Gemini models to use and the daily call cap, chosen at runtime in
 *  Settings → AI provider (no restart, no model name in the code). The env vars
 *  GEMINI_MODEL / GEMINI_FALLBACK_MODEL / AI_DAILY_CALL_CAP are only starting
 *  values used until an admin saves something. */
@Service
public class AiConfigService {

    static final String KEY_MODEL = "gemini_model";
    static final String KEY_FALLBACK = "gemini_fallback_model";
    static final String KEY_CAP = "ai_daily_call_cap";
    static final String KEY_LAST_TEST = "ai_last_test";
    /** Model ids as Google lists them, e.g. "some-model-001" (no "models/" prefix needed). */
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._-]{1,80}");

    private final AppSettingRepository settings;
    private final ObjectMapper json;
    private final String envModel;
    private final String envFallback;
    private final int envCap;

    public AiConfigService(AppSettingRepository settings, ObjectMapper json,
                           @Value("${app.gemini.model:}") String envModel,
                           @Value("${app.gemini.fallback-model:}") String envFallback,
                           @Value("${app.ai.daily-call-cap:2000}") int envCap) {
        this.settings = settings;
        this.json = json;
        this.envModel = blankToNull(envModel);
        this.envFallback = blankToNull(envFallback);
        this.envCap = envCap;
    }

    public record AiConfigRequest(String model, String fallback_model, Integer daily_call_cap) {}

    public record AiConfigView(String model, String fallback_model, int daily_call_cap,
                               String model_source, ConnectionTest last_test) {}

    /** Result of the connection test, also kept as the "last test". Never holds the key. */
    public record ConnectionTest(boolean ok, String model, String fallback_model, boolean model_ok, Boolean fallback_ok,
                                 long latency_ms, String error_code, String message, Instant tested_at) {}

    /** The primary model, or null when none is chosen yet. */
    public String model() {
        return plain(KEY_MODEL).orElse(envModel);
    }

    public String fallbackModel() {
        return plain(KEY_FALLBACK).orElse(envFallback);
    }

    public int dailyCallCap() {
        return plain(KEY_CAP).map(Integer::parseInt).orElse(envCap);
    }

    @Transactional(readOnly = true)
    public AiConfigView view() {
        String source = plain(KEY_MODEL).isPresent() ? "settings" : envModel != null ? "environment" : "none";
        return new AiConfigView(model(), fallbackModel(), dailyCallCap(), source, lastTest());
    }

    @Transactional
    public AiConfigView save(AiConfigRequest req, User admin) {
        if (req == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Nothing to save.");
        if (req.model() != null) putModel(KEY_MODEL, req.model(), admin);
        if (req.fallback_model() != null) putModel(KEY_FALLBACK, req.fallback_model(), admin);
        if (req.daily_call_cap() != null) {
            if (req.daily_call_cap() < 0 || req.daily_call_cap() > 1_000_000) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The daily call cap must be between 0 and 1,000,000.");
            }
            put(KEY_CAP, String.valueOf(req.daily_call_cap()), admin);
        }
        String m = model(), f = fallbackModel();
        if (m != null && m.equals(f)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The fallback model must differ from the main model.");
        }
        return view();
    }

    public ConnectionTest lastTest() {
        return plain(KEY_LAST_TEST).map(v -> {
            try { return json.readValue(v, ConnectionTest.class); } catch (Exception e) { return null; }
        }).orElse(null);
    }

    @Transactional
    public void saveLastTest(ConnectionTest t) {
        try {
            put(KEY_LAST_TEST, json.writeValueAsString(t), null);
        } catch (Exception ignored) {
            // the last-test display is a convenience; never fail the test over it
        }
    }

    /** "" clears the setting (back to the environment value, if any). */
    private void putModel(String key, String value, User admin) {
        String v = value.strip().replaceFirst("^models/", "");
        if (v.isEmpty()) {
            settings.deleteById(key);
            return;
        }
        if (!MODEL_ID.matcher(v).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That doesn't look like a Gemini model id.");
        }
        put(key, v, admin);
    }

    private void put(String key, String value, User admin) {
        AppSetting s = settings.findById(key).orElse(new AppSetting());
        s.setKey(key);
        s.setValue(value);
        s.setUpdatedBy(admin == null ? null : admin.publicId());
        s.setUpdatedAt(Instant.now());
        settings.save(s);
    }

    private Optional<String> plain(String key) {
        return settings.findById(key).map(AppSetting::getValue).filter(v -> v != null && !v.isBlank());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
