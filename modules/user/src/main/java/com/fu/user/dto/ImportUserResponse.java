package com.fu.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportUserResponse {
    /** Users whose accounts were created for the first time. */
    private List<UserResponse> created;
    /** Users who already existed and had their profile updated. */
    private List<UserResponse> updated;
}
