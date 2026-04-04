package com.fu.clazz.dto.response;

import com.fu.clazz.entity.ClassStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Lightweight response for learner's enrolled classes.
 * Contains only essential information: id, name, and status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearnerClassResponse {

    private UUID id;
    private String name;
    private ClassStatus status;
}
