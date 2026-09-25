package com.smartstaff.service;

import com.smartstaff.dto.request.UniversalExecuteRequest;
import com.smartstaff.dto.response.RunScreeningResponse;
import com.smartstaff.entity.User;

/** Backs POST /api/universal_execute — the recruiter assistant chat on
 *  VoiceScreening.jsx. Response shape is identical to run_screening's own
 *  {reply, table_data} (see RunScreeningResponse's javadoc), which is
 *  exactly what the frontend reads regardless of which endpoint produced
 *  it, so there's no separate response DTO. */
public interface RecruiterChatService {

    RunScreeningResponse execute(UniversalExecuteRequest req, User requester);
}
