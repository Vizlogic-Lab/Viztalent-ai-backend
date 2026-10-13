package com.smartstaff.proctoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.entity.AppSetting;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.AppSettingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Settings → Proctoring (setting proctoring_config, JSON). */
@Service
public class ProctoringSettingsService {

    static final String KEY = "proctoring_config";

    private final AppSettingRepository settings;
    private final ObjectMapper json;

    public ProctoringSettingsService(AppSettingRepository settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public ProctoringSettings current() {
        return settings.findById(KEY).map(AppSetting::getValue).filter(v -> v != null && !v.isBlank()).map(v -> {
            try {
                return json.readValue(v, ProctoringSettings.class).withDefaults();
            } catch (Exception e) {
                return ProctoringSettings.DEFAULTS;
            }
        }).orElse(ProctoringSettings.DEFAULTS);
    }

    @Transactional
    public ProctoringSettings save(ProctoringSettings req, User admin) {
        if (req == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Nothing to save.");
        String problem = req.validate();
        if (problem != null) throw new ApiException(HttpStatus.BAD_REQUEST, problem);
        AppSetting s = settings.findById(KEY).orElse(new AppSetting());
        s.setKey(KEY);
        try {
            s.setValue(json.writeValueAsString(req));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Couldn't save the proctoring settings.");
        }
        s.setUpdatedBy(admin == null ? null : admin.publicId());
        s.setUpdatedAt(Instant.now());
        settings.save(s);
        return current();
    }
}
