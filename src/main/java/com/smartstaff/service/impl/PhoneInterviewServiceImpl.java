package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.client.TwilioClient;
import com.smartstaff.dto.request.PlaceCallRequest;
import com.smartstaff.dto.response.CallStatusResponse;
import com.smartstaff.dto.response.PlaceCallResponse;
import com.smartstaff.dto.response.TranscriptTurnResponse;
import com.smartstaff.entity.Interview;
import com.smartstaff.entity.InterviewTurn;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.InterviewRepository;
import com.smartstaff.repository.InterviewTurnRepository;
import com.smartstaff.service.PhoneInterviewService;
import com.smartstaff.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Outbound Twilio phone interviews. The call flow is two webhook
 *  endpoints Twilio drives by itself once {@link #placeCall} dials out:
 *
 *  1. Twilio connects the call and requests {@link #voiceWebhook} — TwiML
 *     says the intro, then <Gather>s speech for question 0, with `action`
 *     pointing at answerWebhook/0.
 *  2. Each {@link #answerWebhook} call saves the just-gathered answer for
 *     `seq`, then either <Gather>s the next question (action bumped to
 *     seq+1) or, once every question is answered, says the outro and
 *     <Hangup/>s — marking the interview COMPLETED right there, since a
 *     phone interview has no separate client-side "save" step the way
 *     browser/self-service modes do (nobody's browser is in this call).
 *  3. Twilio's StatusCallback webhook ({@link #statusCallback}) updates
 *     twilio_call_status independently of the above, and is the fallback
 *     that marks the interview FAILED if the callee never answers at all.
 *
 *  Every webhook is validated against Twilio's X-Twilio-Signature by the
 *  controller before it reaches this class — see TwilioSignatureValidator.
 *
 *  UNVERIFIED beyond code review and the real (correctly-rejected) Twilio
 *  REST call from placeCall: nothing here can be exercised without a real
 *  Twilio account dialing a real phone through a real public tunnel — see
 *  docs/FEATURES.md's Phase 8 section. */
@Service
public class PhoneInterviewServiceImpl implements PhoneInterviewService {

    private static final Logger log = LoggerFactory.getLogger(PhoneInterviewServiceImpl.class);
    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "busy", "failed", "no-answer", "canceled");
    private static final String GATHER_TIMEOUT_SECONDS = "8";

    private final InterviewRepository interviewRepository;
    private final InterviewTurnRepository interviewTurnRepository;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final TwilioClient twilioClient;

    public PhoneInterviewServiceImpl(InterviewRepository interviewRepository,
                                      InterviewTurnRepository interviewTurnRepository,
                                      SettingsService settingsService,
                                      ObjectMapper objectMapper,
                                      TwilioClient twilioClient) {
        this.interviewRepository = interviewRepository;
        this.interviewTurnRepository = interviewTurnRepository;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.twilioClient = twilioClient;
    }

    @Override
    @Transactional
    public PlaceCallResponse placeCall(PlaceCallRequest req) {
        UUID interviewId;
        try {
            interviewId = UUID.fromString(req.interview_id().trim());
        } catch (Exception e) {
            return PlaceCallResponse.error("Unknown interview.");
        }
        Interview interview = interviewRepository.findById(interviewId).orElse(null);
        if (interview == null) {
            return PlaceCallResponse.error("Interview not found — call /api/interview/prepare first.");
        }

        String sid = settingsService.getTwilioAccountSidOrNull();
        String token = settingsService.getTwilioAuthTokenOrNull();
        String from = settingsService.getTwilioFromNumberOrNull();
        if (sid == null || token == null || from == null || from.isBlank()) {
            return PlaceCallResponse.error("Twilio isn't fully configured — add an account SID, auth token, and from-number in Settings.");
        }

        String base = settingsService.getPublicBaseUrl();
        String voiceUrl = base + "/api/interview/twiml/voice/" + interviewId;
        String statusUrl = base + "/api/interview/twiml/status";

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", req.phone());
        form.add("From", from);
        form.add("Url", voiceUrl);
        form.add("StatusCallback", statusUrl);
        form.add("StatusCallbackMethod", "POST");
        for (String event : List.of("initiated", "ringing", "answered", "completed")) {
            form.add("StatusCallbackEvent", event);
        }

        String raw;
        try {
            raw = twilioClient.createCall(sid, token, form);
        } catch (Exception e) {
            log.warn("Twilio place_call failed for interview {}: {}", interviewId, e.toString());
            return PlaceCallResponse.error("Twilio rejected this call: " + shortMessage(e));
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            String callSid = root.path("sid").asText(null);
            if (callSid == null) {
                return PlaceCallResponse.error("Twilio accepted the request but returned no call id.");
            }
            interview.setMode("PHONE");
            interview.setTwilioCallSid(callSid);
            interview.setTwilioCallStatus(root.path("status").asText("queued"));
            interviewRepository.save(interview);
            return PlaceCallResponse.ok(callSid);
        } catch (Exception e) {
            log.warn("Could not parse Twilio's place_call response for interview {}: {}", interviewId, e.toString());
            return PlaceCallResponse.error("Twilio returned something unexpected.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CallStatusResponse callStatus(UUID interviewId) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Interview not found."));
        List<InterviewTurn> turns = interviewTurnRepository.findByInterviewIdOrderBySeqAsc(interviewId);

        String status = interview.getTwilioCallStatus() != null ? interview.getTwilioCallStatus() : "queued";
        int answered = (int) turns.stream().filter(t -> t.getAnswer() != null && !t.getAnswer().isBlank()).count();
        List<TranscriptTurnResponse> transcript = turns.stream()
                .map(t -> new TranscriptTurnResponse(t.getCategory(), t.getSkill(), t.getQuestion(), t.getAnswer()))
                .toList();

        return new CallStatusResponse(status, transcript, answered, turns.size(), TERMINAL_STATUSES.contains(status));
    }

    @Override
    @Transactional
    public String voiceWebhook(UUID interviewId) {
        Interview interview = interviewRepository.findById(interviewId).orElse(null);
        if (interview == null) {
            return sayAndHangup(null, "Sorry, we couldn't start this interview.");
        }
        List<InterviewTurn> turns = interviewTurnRepository.findByInterviewIdOrderBySeqAsc(interviewId);
        if (interview.getStartedAt() == null) {
            interview.setStartedAt(Instant.now());
            interviewRepository.save(interview);
        }
        if (turns.isEmpty()) {
            return sayAndHangup(interview.getLanguage(), interview.getOutro());
        }

        StringBuilder xml = new StringBuilder("<Response>");
        xml.append(say(interview.getLanguage(), interview.getIntro()));
        xml.append(gatherQuestion(interviewId, interview.getLanguage(), turns.get(0), 0));
        xml.append("</Response>");
        return xml.toString();
    }

    @Override
    @Transactional
    public String answerWebhook(UUID interviewId, int seq, Map<String, String> params) {
        Interview interview = interviewRepository.findById(interviewId).orElse(null);
        if (interview == null) {
            return sayAndHangup(null, "Sorry, something went wrong with this interview.");
        }
        List<InterviewTurn> turns = interviewTurnRepository.findByInterviewIdOrderBySeqAsc(interviewId);
        if (seq < 0 || seq >= turns.size()) {
            return sayAndHangup(interview.getLanguage(), interview.getOutro());
        }

        InterviewTurn current = turns.get(seq);
        current.setAnswer(params.getOrDefault("SpeechResult", ""));
        interviewTurnRepository.save(current);

        int next = seq + 1;
        if (next < turns.size()) {
            return "<Response>" + gatherQuestion(interviewId, interview.getLanguage(), turns.get(next), next) + "</Response>";
        }

        interview.setStatus("COMPLETED");
        interview.setEndedAt(Instant.now());
        interviewRepository.save(interview);
        return sayAndHangup(interview.getLanguage(), interview.getOutro());
    }

    @Override
    @Transactional
    public void statusCallback(Map<String, String> params) {
        String callSid = params.get("CallSid");
        String callStatus = params.get("CallStatus");
        if (callSid == null || callStatus == null) return;

        interviewRepository.findByTwilioCallSid(callSid).ifPresent(interview -> {
            interview.setTwilioCallStatus(callStatus);
            if (TERMINAL_STATUSES.contains(callStatus)) {
                if (interview.getEndedAt() == null) interview.setEndedAt(Instant.now());
                // "completed" — the call ran its course (answerWebhook may already have
                // marked the interview COMPLETED after the last question; a caller who hangs
                // up early also lands here, and what was said is the transcript). Anything
                // else (busy/failed/no-answer/canceled) means the call never really
                // happened — but never downgrade an interview that already completed.
                if ("completed".equals(callStatus)) {
                    interview.setStatus("COMPLETED");
                } else if (!"COMPLETED".equals(interview.getStatus())) {
                    interview.setStatus("FAILED");
                }
            }
            interviewRepository.save(interview);
        });
    }

    // ── TwiML building ──────────────────────────────────────────────────

    private String gatherQuestion(UUID interviewId, String language, InterviewTurn turn, int seq) {
        String action = settingsService.getPublicBaseUrl() + "/api/interview/twiml/answer/" + interviewId + "/" + seq;
        return "<Gather input=\"speech\" timeout=\"" + GATHER_TIMEOUT_SECONDS + "\" speechTimeout=\"auto\""
                + " language=\"" + escapeXml(language) + "\" action=\"" + escapeXml(action) + "\" method=\"POST\">"
                + say(language, turn.getQuestion())
                + "</Gather>";
    }

    private String say(String language, String text) {
        String lang = (language == null || language.isBlank()) ? "en-IN" : language;
        String safeText = (text == null || text.isBlank()) ? "." : text;
        return "<Say language=\"" + escapeXml(lang) + "\">" + escapeXml(safeText) + "</Say>";
    }

    private String sayAndHangup(String language, String text) {
        return "<Response>" + say(language, text) + "<Hangup/></Response>";
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String shortMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
