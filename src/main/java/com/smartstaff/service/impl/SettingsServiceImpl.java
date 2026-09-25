package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.client.TwilioClient;
import com.smartstaff.dto.request.*;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.AppSetting;
import com.smartstaff.entity.User;
import com.smartstaff.repository.AppSettingRepository;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.CryptoService;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class SettingsServiceImpl implements SettingsService {

    private static final String KEY_GEMINI_API_KEY = "gemini_api_key";
    private static final String KEY_PUBLIC_BASE_URL = "public_base_url";
    private static final String KEY_INVITE_TTL_SECONDS = "invite_ttl_seconds";
    private static final String KEY_TWILIO_SID = "twilio_account_sid";
    private static final String KEY_TWILIO_TOKEN = "twilio_auth_token";
    private static final String KEY_TWILIO_FROM = "twilio_from_number";
    private static final String KEY_PISTON_URL = "piston_url";
    private static final String KEY_PISTON_ENABLED = "piston_enabled";
    private static final String KEY_PISTON_LANGUAGES = "piston_languages";

    private static final long DEFAULT_TTL_SECONDS = 48 * 3600;
    private static final List<String> LOCAL_FALLBACK_LANGUAGES = List.of("python");

    private final AppSettingRepository settings;
    private final CryptoService crypto;
    private final ObjectMapper objectMapper;
    private final GeminiClient geminiClient;
    private final TwilioClient twilioClient;
    // Only for the admin-supplied Piston URL (an arbitrary host, so it can't
    // go through a fixed-base-URL client like Gemini/Twilio). Bounded so an
    // unreachable URL can't hang the Settings save.
    private final RestClient pistonClient = RestClient.builder().requestFactory(pistonRequestFactory()).build();

    public SettingsServiceImpl(AppSettingRepository settings, CryptoService crypto, ObjectMapper objectMapper,
                                GeminiClient geminiClient, TwilioClient twilioClient) {
        this.settings = settings;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
        this.geminiClient = geminiClient;
        this.twilioClient = twilioClient;
    }

    private static SimpleClientHttpRequestFactory pistonRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return factory;
    }

    @Override
    public ConfigResponse getConfig() {
        String geminiKey = decryptedOrNull(KEY_GEMINI_API_KEY);
        boolean hasGeminiKey = geminiKey != null && !geminiKey.isBlank();

        String publicUrl = plain(KEY_PUBLIC_BASE_URL).orElse("http://localhost:8000");
        boolean isLocalhost = publicUrl.contains("localhost") || publicUrl.contains("127.0.0.1") || publicUrl.contains("0.0.0.0");

        String twilioSid = plain(KEY_TWILIO_SID).orElse(null);
        String twilioToken = decryptedOrNull(KEY_TWILIO_TOKEN);
        String twilioFrom = plain(KEY_TWILIO_FROM).orElse(null);
        boolean twilioConfigured = notBlank(twilioSid) && notBlank(twilioToken);

        String pistonUrl = plain(KEY_PISTON_URL).orElse(null);
        boolean pistonEnabled = "true".equals(plain(KEY_PISTON_ENABLED).orElse("false"));
        List<String> languages = pistonEnabled ? readLanguages() : LOCAL_FALLBACK_LANGUAGES;

        long ttlSeconds = plain(KEY_INVITE_TTL_SECONDS).map(Long::parseLong).orElse(DEFAULT_TTL_SECONDS);

        return new ConfigResponse(
                true,
                new GeminiConfigInfo(hasGeminiKey, hasGeminiKey, hasGeminiKey ? geminiClient.model() : null, CryptoService.mask(geminiKey)),
                new PublicUrlConfigInfo(publicUrl, isLocalhost, publicUrl),
                new TwilioConfigInfo(twilioConfigured, CryptoService.mask(twilioSid), CryptoService.mask(twilioToken), twilioFrom),
                new CodeRunnerConfigInfo(pistonUrl, pistonEnabled, languages),
                new InvitesConfigInfo(ttlSeconds, ttlSeconds / 3600)
        );
    }

    @Override
    @Transactional
    public SaveStatusResponse saveGeminiKey(GeminiKeyRequest req, User admin) {
        if (req.api_key() == null || req.api_key().isBlank()) {
            settings.deleteById(KEY_GEMINI_API_KEY);
        } else {
            put(KEY_GEMINI_API_KEY, crypto.encrypt(req.api_key().trim()), admin);
        }
        return SaveStatusResponse.OK;
    }

    @Override
    public GeminiTestResponse testGeminiKey() {
        String key = decryptedOrNull(KEY_GEMINI_API_KEY);
        if (key == null || key.isBlank()) {
            return new GeminiTestResponse(false, "No Gemini API key configured.", null, null);
        }
        try {
            String body = geminiClient.listModels(key);
            JsonNode root = objectMapper.readTree(body);
            String model = geminiClient.model();
            for (JsonNode m : root.path("models")) {
                String name = m.path("name").asText("");
                if (name.contains("flash")) { model = name.replaceFirst("^models/", ""); break; }
            }
            return new GeminiTestResponse(true, null, model, null);
        } catch (RestClientException e) {
            return new GeminiTestResponse(false, "Gemini did not accept this key: " + shortMessage(e), null, null);
        } catch (Exception e) {
            return new GeminiTestResponse(false, "Could not reach Gemini: " + shortMessage(e), null, null);
        }
    }

    @Override
    @Transactional
    public PublicUrlSaveResponse savePublicUrl(PublicUrlRequest req, User admin) {
        String url = req.public_base_url().trim().replaceAll("/+$", "");
        put(KEY_PUBLIC_BASE_URL, url, admin);
        boolean isLocalhost = url.contains("localhost") || url.contains("127.0.0.1") || url.contains("0.0.0.0");
        return new PublicUrlSaveResponse(url, url, isLocalhost);
    }

    @Override
    @Transactional
    public InviteTtlSaveResponse saveInviteTtl(InviteTtlRequest req, User admin) {
        put(KEY_INVITE_TTL_SECONDS, String.valueOf(req.ttl_seconds()), admin);
        return new InviteTtlSaveResponse(req.ttl_seconds(), req.ttl_seconds() / 3600);
    }

    @Override
    @Transactional
    public TwilioSaveResponse saveTwilio(TwilioConfigRequest req, User admin) {
        // Blank fields leave the existing value alone — see TwilioConfigRequest's
        // javadoc for why this deliberately differs from the original app.
        if (notBlank(req.account_sid())) put(KEY_TWILIO_SID, req.account_sid().trim(), admin);
        if (notBlank(req.auth_token())) put(KEY_TWILIO_TOKEN, crypto.encrypt(req.auth_token().trim()), admin);
        if (notBlank(req.from_number())) put(KEY_TWILIO_FROM, req.from_number().trim(), admin);

        boolean configured = notBlank(plain(KEY_TWILIO_SID).orElse(null))
                && notBlank(decryptedOrNull(KEY_TWILIO_TOKEN));
        return new TwilioSaveResponse(configured);
    }

    @Override
    public TwilioTestResponse testTwilio() {
        String sid = plain(KEY_TWILIO_SID).orElse(null);
        String token = decryptedOrNull(KEY_TWILIO_TOKEN);
        String from = plain(KEY_TWILIO_FROM).orElse(null);
        if (!notBlank(sid) || !notBlank(token)) {
            return new TwilioTestResponse(false, "Twilio not configured.", null, null);
        }
        try {
            String body = twilioClient.fetchAccount(sid, token);
            JsonNode root = objectMapper.readTree(body);
            String accountName = root.path("friendly_name").asText(sid);
            return new TwilioTestResponse(true, null, accountName, from);
        } catch (RestClientException e) {
            return new TwilioTestResponse(false, "Twilio rejected these credentials: " + shortMessage(e), null, null);
        } catch (Exception e) {
            return new TwilioTestResponse(false, "Could not reach Twilio: " + shortMessage(e), null, null);
        }
    }

    @Override
    @Transactional
    public CodeRunnerConfigInfo savePistonUrl(PistonUrlRequest req, User admin) {
        String url = req.piston_url() == null ? "" : req.piston_url().trim().replaceAll("/+$", "");

        if (url.isEmpty()) {
            settings.deleteById(KEY_PISTON_URL);
            put(KEY_PISTON_ENABLED, "false", admin);
            put(KEY_PISTON_LANGUAGES, joinLanguages(LOCAL_FALLBACK_LANGUAGES), admin);
            return new CodeRunnerConfigInfo(null, false, LOCAL_FALLBACK_LANGUAGES);
        }

        put(KEY_PISTON_URL, url, admin);
        try {
            String body = pistonClient.get().uri(url + "/runtimes").retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(body);
            Set<String> languages = new LinkedHashSet<>();
            for (JsonNode runtime : root) languages.add(runtime.path("language").asText());
            List<String> list = new ArrayList<>(languages);
            put(KEY_PISTON_ENABLED, "true", admin);
            put(KEY_PISTON_LANGUAGES, joinLanguages(list), admin);
            return new CodeRunnerConfigInfo(url, true, list);
        } catch (Exception e) {
            // URL saved so the recruiter doesn't have to retype it, but marked
            // disabled until it's reachable — same "local fallback" messaging
            // Settings.jsx shows either way.
            put(KEY_PISTON_ENABLED, "false", admin);
            put(KEY_PISTON_LANGUAGES, joinLanguages(LOCAL_FALLBACK_LANGUAGES), admin);
            return new CodeRunnerConfigInfo(url, false, LOCAL_FALLBACK_LANGUAGES);
        }
    }

    @Override
    public SimpleResponse reset() {
        // The original app's /api/reset wiped its single global chat session.
        // This backend has no equivalent global session to wipe (all state is
        // job-scoped and already deletable per-job via DELETE /api/jobs/{id}),
        // so this is intentionally a no-op — see docs/FEATURES.md open
        // question #3 ("should /api/reset exist in production?").
        return SimpleResponse.OK;
    }

    @Override
    public String getGeminiApiKeyOrNull() {
        return decryptedOrNull(KEY_GEMINI_API_KEY);
    }

    @Override
    public String getPublicBaseUrl() {
        return plain(KEY_PUBLIC_BASE_URL).orElse("http://localhost:8000");
    }

    @Override
    public long getInviteTtlSeconds() {
        return plain(KEY_INVITE_TTL_SECONDS).map(Long::parseLong).orElse(DEFAULT_TTL_SECONDS);
    }

    @Override
    public boolean isTwilioConfigured() {
        return notBlank(plain(KEY_TWILIO_SID).orElse(null)) && notBlank(decryptedOrNull(KEY_TWILIO_TOKEN));
    }

    @Override
    public String getTwilioFromNumberOrNull() {
        return plain(KEY_TWILIO_FROM).orElse(null);
    }

    @Override
    public String getTwilioAccountSidOrNull() {
        return plain(KEY_TWILIO_SID).orElse(null);
    }

    @Override
    public String getTwilioAuthTokenOrNull() {
        return decryptedOrNull(KEY_TWILIO_TOKEN);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void put(String key, String value, User admin) {
        AppSetting setting = settings.findById(key).orElse(new AppSetting());
        setting.setKey(key);
        setting.setValue(value);
        setting.setUpdatedBy(admin == null ? null : admin.publicId());
        setting.setUpdatedAt(Instant.now());
        settings.save(setting);
    }

    private Optional<String> plain(String key) {
        return settings.findById(key).map(AppSetting::getValue).filter(v -> v != null && !v.isBlank());
    }

    private String decryptedOrNull(String key) {
        return plain(key).map(crypto::decrypt).orElse(null);
    }

    private List<String> readLanguages() {
        return plain(KEY_PISTON_LANGUAGES)
                .map(v -> List.of(v.split(",")))
                .orElse(LOCAL_FALLBACK_LANGUAGES);
    }

    private static String joinLanguages(List<String> languages) {
        return String.join(",", languages);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String shortMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
