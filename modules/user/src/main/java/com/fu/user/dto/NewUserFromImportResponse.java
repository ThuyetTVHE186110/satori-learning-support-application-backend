package com.fu.user.dto;

import com.fu.user.entity.UserRole;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Builder
@Data
public class NewUserFromImportResponse {
    private String email;
    private String fullName;
    private LocalDate birthDay;
    private String phoneNumber;
    private UserRole role;
}
