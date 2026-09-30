package com.smartstaff.dto.request;

/** One answer in the `transcript` array posted to save / save_by_token.
 *  Answers are matched to the stored interview_turns by `seq` (or by array
 *  position when seq is absent); category, skill and question are accepted
 *  for compatibility but ignored, so a client can't rewrite the questions. */
public record TranscriptTurnRequest(Integer seq, String category, String skill, String question, String answer) {}
