package com.smartstaff.dto.response;

/** Field names are exactly what Candidates.jsx/Dashboard.jsx/Analytics.jsx
 *  read off each row (File_Name, Candidate_Name, ...) — the original app's
 *  own convention, kept verbatim rather than translated to camelCase so the
 *  frontend needs zero changes. See smartstaff/frontend/FEATURES.md. */
public record CandidateRowResponse(
        String File_Name,
        String Candidate_Name,
        String Email,
        String Phone,
        int Years_Experience,
        int Fit_Score_Out_Of_100,
        int Matched_Count,
        int Total_Required,
        String Key_Strengths,
        String Missing_Skills,
        String Score_Breakdown,
        String Job_Id,
        Integer AI_Match_Score,
        String AI_Match_Status,
        String AI_Match_Summary,
        String AI_Match_Strengths,
        String AI_Match_Gaps
) {}
