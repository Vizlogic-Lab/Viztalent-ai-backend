package com.smartstaff.service;

import com.smartstaff.dto.request.LoginRequest;
import com.smartstaff.dto.request.SignupRequest;
import com.smartstaff.dto.response.AccountResponse;
import com.smartstaff.dto.response.AccountsResponse;
import com.smartstaff.dto.response.AuthResponse;
import com.smartstaff.entity.User;

public interface AuthService {

    AuthResponse login(LoginRequest request);

    AuthResponse signup(SignupRequest request, boolean requesterIsAdmin);

    AccountResponse me(User user);

    AccountsResponse accounts();

    void setApproval(String employeeId, boolean approved);
}
