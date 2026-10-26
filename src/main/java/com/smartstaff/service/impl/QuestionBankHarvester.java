package com.smartstaff.service.impl;

import com.smartstaff.entity.*;
import com.smartstaff.repository.QuestionBankItemRepository;
import com.smartstaff.repository.QuestionBankUploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Copies a validated, generic AI-drafted question (with its answer) into the
 *  question bank so later assessments can reuse it. Project-specific and
 *  SCENARIO questions, bank-sourced questions and duplicates are skipped.
 *  Never throws: a harvest problem must not fail the assessment. */
@Component
public class QuestionBankHarvester {

    static final String SOURCE = "AI_GENERATED";
    static final String UPLOAD_NAME = "AI-generated questions (auto)";

    private static final Logger log = LoggerFactory.getLogger(QuestionBankHarvester.class);

    private final QuestionBankItemRepository items;
    private final QuestionBankUploadRepository uploads;
    private final TransactionTemplate tx;

    public QuestionBankHarvester(QuestionBankItemRepository items, QuestionBankUploadRepository uploads,
                                 PlatformTransactionManager transactionManager) {
        this.items = items;
        this.uploads = uploads;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    static boolean eligible(AssessmentQuestion q) {
        return "AI".equals(q.getOrigin()) && q.isValidated() && !q.isProjectSpecific()
                && q.getType() != QuestionType.SCENARIO;
    }

    public synchronized void harvest(AssessmentQuestion q, Map<String, String> naiveSolution) {
        if (!eligible(q)) return;
        try {
            String hash = hash(q);
            if (items.existsByContentHash(hash)) return;
            tx.executeWithoutResult(s -> {
                QuestionBankUpload upload = uploads.findFirstByFilename(UPLOAD_NAME)
                        .orElseGet(() -> uploads.save(new QuestionBankUpload(UPLOAD_NAME, "system", 0)));
                items.save(toItem(q, naiveSolution, upload, hash));
                upload.setItemCount(upload.getItemCount() + 1);
                uploads.save(upload);
            });
        } catch (Exception e) {
            log.warn("Could not store AI question in the bank: {}", e.getMessage());
        }
    }

    private static QuestionBankItem toItem(AssessmentQuestion q, Map<String, String> naive, QuestionBankUpload upload,
                                           String hash) {
        QuestionBankItem item = new QuestionBankItem();
        item.setUpload(upload);
        item.setSource(SOURCE);
        item.setContentHash(hash);
        item.setType(q.getType().name());
        item.setDimension(q.getDimension());
        item.setCompetency(q.getCompetency());
        item.setLevel(q.getLevel());
        item.setSkill(q.getSkill());
        item.setDifficulty(q.getDifficulty());
        item.setPoints(q.getPoints() > 0 ? q.getPoints() : null);
        item.setTimeEstimateSec(q.getTimeEstimateSec());
        item.setTitle(q.getTitle());
        item.setPrompt(q.getPrompt());
        item.setConstraints(q.getConstraints());
        item.setInputFormat(q.getInputFormat());
        item.setOutputFormat(q.getOutputFormat());
        item.setOptions(new ArrayList<>(q.getOptions()));
        item.setCorrectIndices(new ArrayList<>(q.getCorrectIndices()));
        item.setLanguages(new ArrayList<>(q.getLanguages()));
        item.setStarterCode(new LinkedHashMap<>(q.getStarterCode()));
        item.setReferenceSolution(new LinkedHashMap<>(q.getReferenceSolution()));
        item.setNaiveSolution(new LinkedHashMap<>(naive == null ? Map.of() : naive));
        item.setBuggyCode(new LinkedHashMap<>(q.getBuggyCode()));
        item.setModelAnswer(q.getModelAnswer());
        item.setKeyPoints(new ArrayList<>(q.getKeyPoints()));
        item.setRubric(new ArrayList<>(q.getRubric()));
        item.setExplanation(q.getExplanation());
        item.setExpectedComplexity(q.getExpectedComplexity());
        item.setBugDescriptions(new ArrayList<>(q.getBugDescriptions()));
        item.setValidated(true);
        item.setValidationLog("Harvested from a generated assessment.\n"
                + (q.getValidationLog() == null ? "" : q.getValidationLog()));
        for (QuestionTestCase t : q.getTestCases()) {
            QuestionBankTestCase tc = new QuestionBankTestCase();
            tc.setItem(item);
            tc.setSeq(t.getSeq());
            tc.setInput(t.getInput());
            tc.setExpectedOutput(t.getExpectedOutput());
            tc.setVisible(t.isVisible());
            tc.setCategory(t.getCategory());
            tc.setWeight(t.getWeight());
            tc.setFloatTolerance(t.getFloatTolerance());
            item.getTestCases().add(tc);
        }
        return item;
    }

    /** Same question text (and options / code) hashes the same regardless of case and spacing. */
    static String hash(AssessmentQuestion q) {
        StringBuilder sb = new StringBuilder(q.getType().name()).append('|').append(norm(q.getPrompt()));
        q.getOptions().forEach(o -> sb.append('|').append(norm(o)));
        new TreeMap<>(q.getStarterCode()).forEach((lang, code) -> sb.append('|').append(lang).append(':').append(norm(code)));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
