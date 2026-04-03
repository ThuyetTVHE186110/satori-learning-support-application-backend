package com.fu.user.controller;

import com.fu.common.response.ApiResponse;
import com.fu.user.service.UserService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/users")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@RequiredArgsConstructor
public class PublicUserController {
    UserService userService;

    @GetMapping("/teachers")
    public ResponseEntity<ApiResponse> getAllTeachers() {
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách giảng viên thành công.",userService.getAllTeachers()));
    }
}
