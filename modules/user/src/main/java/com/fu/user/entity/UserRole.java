package com.fu.user.entity;

/**
 * User roles in the system.
 * Maps to PostgreSQL ENUM: user_role ('LEARNER', 'TEACHER', 'CONTENT_MANAGER','OPERATION_MANAGER', 'ADMIN')
 * 
 * Note: User management operations (CRUD, restore) are restricted to ADMIN only.
 */
public enum UserRole {
    LEARNER,    // Student/Learner - can access learning features
    TEACHER,    // Teacher - can manage classes, assignments
    CONTENT_MANAGER,    // CONTENT_MANAGER - can manage learning content (e.g., courses, materials)
    OPERATION_MANAGER, // OPERATION_MANAGER - can manage operational aspects
    ADMIN       // Legacy - full admin rights
}
