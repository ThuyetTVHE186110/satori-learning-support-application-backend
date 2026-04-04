package com.fu.assignment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;

/**
 * Test-only bootstrap for @SpringBootTest integration tests in the assignment module.
 *
 * Enables full component scan and entity scan to support real database integration tests
 * that verify AI-generated response schema contracts.
 *
 * Use: @SpringBootTest(classes = AssignmentTestApplication.class) or set as default in test properties.
 */
@SpringBootApplication
@EntityScan(basePackages = "com.fu")
@ComponentScan(basePackages = "com.fu")
public class AssignmentTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(AssignmentTestApplication.class, args);
    }
}

