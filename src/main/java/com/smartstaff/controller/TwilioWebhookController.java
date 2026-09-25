package com.smartstaff.controller;

import com.smartstaff.service.PhoneInterviewService;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.TwilioSignatureValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Public, form-encoded, XML-in/XML-out — Twilio's own request/response
 *  shape, deliberately not this app's usual JSON {ok, message} contract
 *  (see PhoneInterviewServiceImpl's class javadoc for the call flow).
 *  Every route here is permitAll in SecurityConfig and instead gated by
 *  Twilio's own request signature, validated against the *configured*
 *  public base URL — never HttpServletRequest's own idea of its URL, which
 *  behind a tunnel would say "localhost:8000" and never match what Twilio
 *  actually signed. */
@RestController
@RequestMapping("/api/interview/twiml")
public class TwilioWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TwilioWebhookController.class);
    private static final String SIGNATURE_HEADER = "X-Twilio-Signature";
    // Explicit charset: interview text can be Hindi/Tamil/etc., and bare `text/xml` leaves the
    // encoding to the consumer's guess (RFC 3023 even defaults it to US-ASCII).
    private static final MediaType TEXT_XML_UTF8 = new MediaType("text", "xml", StandardCharsets.UTF_8);
    private static final String REJECTED_TWIML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Reject/></Response>";

    private final PhoneInterviewService phoneInterviewService;
    private final SettingsService settingsService;
    private final TwilioSignatureValidator signatureValidator;

    public TwilioWebhookController(PhoneInterviewService phoneInterviewService,
                                    SettingsService settingsService,
                                    TwilioSignatureValidator signatureValidator) {
        this.phoneInterviewService = phoneInterviewService;
        this.settingsService = settingsService;
        this.signatureValidator = signatureValidator;
    }

    @PostMapping(value = "/voice/{interviewId}", produces = MediaType.TEXT_XML_VALUE)
    public ResponseEntity<String> voice(@PathVariable UUID interviewId,
                                         @RequestParam Map<String, String> params,
                                         HttpServletRequest request) {
        if (!signatureValid(request, params)) return rejected();
        return xml(phoneInterviewService.voiceWebhook(interviewId));
    }

    @PostMapping(value = "/answer/{interviewId}/{seq}", produces = MediaType.TEXT_XML_VALUE)
    public ResponseEntity<String> answer(@PathVariable UUID interviewId, @PathVariable int seq,
                                          @RequestParam Map<String, String> params,
                                          HttpServletRequest request) {
        if (!signatureValid(request, params)) return rejected();
        return xml(phoneInterviewService.answerWebhook(interviewId, seq, params));
    }

    @PostMapping("/status")
    public ResponseEntity<Void> status(@RequestParam Map<String, String> params, HttpServletRequest request) {
        if (!signatureValid(request, params)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        phoneInterviewService.statusCallback(params);
        return ResponseEntity.ok().build();
    }

    // ── shared ──────────────────────────────────────────────────────────

    private boolean signatureValid(HttpServletRequest request, Map<String, String> params) {
        String token = settingsService.getTwilioAuthTokenOrNull();
        if (token == null) {
            log.warn("Rejected a Twilio webhook — Twilio isn't configured.");
            return false;
        }
        String fullUrl = settingsService.getPublicBaseUrl() + request.getRequestURI();
        String signature = request.getHeader(SIGNATURE_HEADER);
        boolean valid = signatureValidator.isValid(token, fullUrl, params, signature);
        if (!valid) {
            log.warn("Rejected a Twilio webhook with an invalid X-Twilio-Signature for {}", request.getRequestURI());
        }
        return valid;
    }

    private static ResponseEntity<String> xml(String body) {
        return ResponseEntity.ok().contentType(TEXT_XML_UTF8).body(body);
    }

    private static ResponseEntity<String> rejected() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(TEXT_XML_UTF8).body(REJECTED_TWIML);
    }
}
