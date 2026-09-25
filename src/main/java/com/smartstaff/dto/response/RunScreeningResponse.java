package com.smartstaff.dto.response;

import java.util.List;

/** See VoiceScreening.jsx's runScreening(): reads response.data.reply and
 *  response.data.table_data (then tags each row with Job_Id client-side —
 *  we already set it server-side too, redundant but harmless). */
public record RunScreeningResponse(String reply, List<CandidateRowResponse> table_data) {}
