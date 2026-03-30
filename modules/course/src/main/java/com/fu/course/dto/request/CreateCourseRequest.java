package com.fu.course.dto.request;

import com.fu.course.entity.Course.JlptLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a course
 * MANAGER only
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCourseRequest {

    @NotBlank(message = "Tên khóa học không được để trống")
    @Size(min = 1, max = 100, message = "Tên khóa học phải có độ dài từ 1 đến 100 ký tự")
    private String name;

    @Size(max = 5000, message = "Mô tả không được vượt quá 5000 ký tự")
    private String description;

    @NotNull(message = "Cấp độ JLPT là bắt buộc")
    @Pattern(regexp = "N[1-5]", message = "Cấp độ JLPT phải có định dạng N1, N2, N3, N4 hoặc N5")
    private JlptLevel jlptLevel;

    private Integer orderIndex;
}
