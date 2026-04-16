package com.fu.clazz.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Learner information for class preview/import
 * Contains both ID and full user details to display in preview and use in confirm
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearnerInfo {
    private UUID id;                // học viên ID (để persist)
    private String email;           // học viên email (unique identifier)
    private String fullName;        // học viên name (firstName + lastName)
    private LocalDate dateOfBirth;  // ngày sinh
    private String phoneNumber;     // số điện thoại
}
