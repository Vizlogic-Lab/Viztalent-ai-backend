package com.smartstaff.mapper;

import com.smartstaff.dto.response.AccountResponse;
import com.smartstaff.entity.Role;
import com.smartstaff.entity.AccountStatus;
import com.smartstaff.entity.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public AccountResponse toAccountResponse(User u) {
        return new AccountResponse(
                u.publicId(),
                u.getRole().name().toLowerCase(),
                u.getName(),
                u.getEmail(),
                u.getEmployeeId(),
                u.getRole() == Role.USER ? u.getStatus() == AccountStatus.APPROVED : null,
                u.getLastSeenAt(),
                u.getLastLoginAt(),
                u.getCreatedAt()
        );
    }
}
