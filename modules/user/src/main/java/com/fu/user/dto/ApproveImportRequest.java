package com.fu.user.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ApproveImportRequest {
    private List<ImportCreateUserRequest> createUserRequests;
    private List<ImportUpdateUserRequest> updateUserRequests;
}
