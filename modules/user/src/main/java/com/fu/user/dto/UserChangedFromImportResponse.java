package com.fu.user.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Builder
@Data
public class UserChangedFromImportResponse {
    private String oldEmail;
    private String newEmail;
    private String oldFullName;
    private String newFullName;
    private LocalDate oldDateOfBirth;
    private LocalDate newDateOfBirth;
    private String oldPhoneNumber;
    private String newPhoneNumber;
}
