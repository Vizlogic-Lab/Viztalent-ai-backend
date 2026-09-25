package com.smartstaff.dto.response;

import java.util.List;

public record AccountsResponse(boolean ok, List<AccountResponse> admins, List<AccountResponse> users) {}
