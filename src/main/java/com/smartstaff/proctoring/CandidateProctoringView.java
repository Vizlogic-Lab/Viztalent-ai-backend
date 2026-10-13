package com.smartstaff.proctoring;

/** What the candidate's consent screen needs before the clock starts. */
public record CandidateProctoringView(boolean ok, ProctoringSettings settings) {}
