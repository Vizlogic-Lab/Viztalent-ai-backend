package com.smartstaff.dto.response;

import java.util.List;

public record InviteMintResponse(boolean ok, List<InviteResponse> invites) {}
