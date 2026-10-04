package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.entity.AppSetting;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.AppSettingRepository;
import com.smartstaff.util.CryptoService;
import com.smartstaff.util.EmailTemplates;
import com.smartstaff.util.EmailTemplates.Kind;
import com.smartstaff.util.EmailTemplates.Template;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** F17: the SMTP server (password encrypted like the other secrets) and the
 *  editable email templates, both kept in app_settings. Admin only. */
@Service
public class EmailSettingsService {

    public record Smtp(String host, int port, String username, String password, String from, boolean tls) {
        /** Never print the password. */
        @Override
        public String toString() {
            return "Smtp[" + host + ":" + port + ", from=" + from + ", tls=" + tls + "]";
        }
    }

    public record SmtpRequest(String host, Integer port, String username, String password, String from_address, Boolean tls) {}

    public record TemplateRequest(String subject, String body) {}

    static final String HOST = "smtp_host", PORT = "smtp_port", USERNAME = "smtp_username", PASSWORD = "smtp_password",
            FROM = "smtp_from", TLS = "smtp_tls", TEMPLATE_PREFIX = "email_template_";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern HOSTNAME = Pattern.compile("^[A-Za-z0-9.-]{1,255}$");

    private final AppSettingRepository settings;
    private final CryptoService crypto;
    private final ObjectMapper json;

    public EmailSettingsService(AppSettingRepository settings, CryptoService crypto, ObjectMapper json) {
        this.settings = settings;
        this.crypto = crypto;
        this.json = json;
    }

    public static boolean looksLikeEmail(String s) {
        return s != null && s.length() <= 320 && EMAIL.matcher(s.trim()).matches();
    }

    // ── SMTP ────────────────────────────────────────────────────────────

    /** Null until a host and a from address are saved — callers fall back to mailto. */
    public Smtp smtpOrNull() {
        String host = plain(HOST), from = plain(FROM);
        if (host == null || from == null) return null;
        String pw = plain(PASSWORD);
        return new Smtp(host, Integer.parseInt(Optional.ofNullable(plain(PORT)).orElse("587")), plain(USERNAME),
                pw == null ? null : crypto.decrypt(pw), from, !"false".equals(plain(TLS)));
    }

    public boolean configured() {
        return smtpOrNull() != null;
    }

    public Map<String, Object> smtpView() {
        Smtp s = smtpOrNull();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("configured", s != null);
        m.put("host", plain(HOST));
        m.put("port", plain(PORT) == null ? 587 : Integer.parseInt(plain(PORT)));
        m.put("username", plain(USERNAME));
        m.put("password", s == null || s.password() == null ? null : CryptoService.mask(s.password()));
        m.put("from_address", plain(FROM));
        m.put("tls", !"false".equals(plain(TLS)));
        return m;
    }

    /** A blank password keeps the saved one. */
    @Transactional
    public Map<String, Object> saveSmtp(SmtpRequest req, User admin) {
        if (req == null || req.host() == null || !HOSTNAME.matcher(req.host().trim()).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "host must be a hostname such as smtp.example.com.");
        }
        int port = req.port() == null ? 587 : req.port();
        if (port < 1 || port > 65535) throw new ApiException(HttpStatus.BAD_REQUEST, "port must be between 1 and 65535.");
        if (!looksLikeEmail(req.from_address())) throw new ApiException(HttpStatus.BAD_REQUEST, "from_address must be an email address.");
        put(HOST, req.host().trim(), admin);
        put(PORT, String.valueOf(port), admin);
        if (req.username() == null || req.username().isBlank()) {
            settings.deleteById(USERNAME);
            settings.deleteById(PASSWORD);
        } else {
            put(USERNAME, req.username().trim(), admin);
            if (req.password() != null && !req.password().isBlank()) put(PASSWORD, crypto.encrypt(req.password()), admin);
        }
        put(FROM, req.from_address().trim(), admin);
        put(TLS, String.valueOf(req.tls() == null || req.tls()), admin);
        return smtpView();
    }

    /** Back to the mailto flow. */
    @Transactional
    public Map<String, Object> clearSmtp() {
        for (String k : List.of(HOST, PORT, USERNAME, PASSWORD, FROM, TLS)) {
            if (settings.existsById(k)) settings.deleteById(k);
        }
        return smtpView();
    }

    // ── templates ───────────────────────────────────────────────────────

    public Template template(Kind kind) {
        String stored = plain(TEMPLATE_PREFIX + kind.name().toLowerCase(Locale.ROOT));
        if (stored != null) {
            try {
                Template t = json.readValue(stored, Template.class);
                if (EmailTemplates.problem(kind, t.subject(), t.body()) == null) return t;
            } catch (Exception ignored) {
                // An unreadable stored template falls back to the default.
            }
        }
        return EmailTemplates.defaults(kind);
    }

    public Map<String, Object> templatesView() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Kind k : Kind.values()) {
            Template t = template(k);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", k.name());
            m.put("subject", t.subject());
            m.put("body", t.body());
            m.put("customised", plain(TEMPLATE_PREFIX + k.name().toLowerCase(Locale.ROOT)) != null);
            m.put("placeholders", k.placeholders().stream().map(p -> "{" + p + "}").toList());
            rows.add(m);
        }
        return Map.of("ok", true, "templates", rows);
    }

    @Transactional
    public Map<String, Object> saveTemplate(String kindName, TemplateRequest req, User admin) {
        Kind kind = kind(kindName);
        String problem = req == null ? "subject and body are required." : EmailTemplates.problem(kind, req.subject(), req.body());
        if (problem != null) throw new ApiException(HttpStatus.BAD_REQUEST, problem);
        try {
            put(TEMPLATE_PREFIX + kind.name().toLowerCase(Locale.ROOT),
                    json.writeValueAsString(new Template(req.subject().trim(), req.body())), admin);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Could not save the template.");
        }
        return templatesView();
    }

    @Transactional
    public Map<String, Object> resetTemplate(String kindName) {
        String key = TEMPLATE_PREFIX + kind(kindName).name().toLowerCase(Locale.ROOT);
        if (settings.existsById(key)) settings.deleteById(key);
        return templatesView();
    }

    private static Kind kind(String name) {
        Kind k = name == null ? null : Kind.parse(name);
        if (k == null) throw new ApiException(HttpStatus.NOT_FOUND, "Unknown email template.");
        return k;
    }

    // ── app_settings ────────────────────────────────────────────────────

    private void put(String key, String value, User admin) {
        AppSetting s = settings.findById(key).orElse(new AppSetting());
        s.setKey(key);
        s.setValue(value);
        s.setUpdatedBy(admin == null ? null : admin.publicId());
        s.setUpdatedAt(Instant.now());
        settings.save(s);
    }

    private String plain(String key) {
        return settings.findById(key).map(AppSetting::getValue).filter(v -> !v.isBlank()).orElse(null);
    }
}
