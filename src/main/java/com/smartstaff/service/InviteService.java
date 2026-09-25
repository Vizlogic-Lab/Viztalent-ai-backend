package com.smartstaff.service;

import com.smartstaff.dto.request.InviteMintRequest;
import com.smartstaff.dto.response.InviteMintResponse;
import com.smartstaff.entity.User;

public interface InviteService {

    InviteMintResponse mint(InviteMintRequest req, User admin);
}
