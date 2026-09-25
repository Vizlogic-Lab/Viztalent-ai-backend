package com.smartstaff.dto.response;

/** One prepared question — see InterviewRoom.jsx / CandidateInterview.jsx
 *  reading q.category / q.skill / q.question. */
public record InterviewQuestionResponse(String category, String skill, String question) {}
