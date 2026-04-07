package com.fu.user.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Builder
@Data
public class UserChangedFromImportResponse {
    private UUID userId;
    private String oldEmail;
    private String newEmail;
    private String oldFullName;
    private String newFullName;
    private LocalDate oldDateOfBirth;
    private LocalDate newDateOfBirth;
    private String oldPhoneNumber;
    private String newPhoneNumber;
}
