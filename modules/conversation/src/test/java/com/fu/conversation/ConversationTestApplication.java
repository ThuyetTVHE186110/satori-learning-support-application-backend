package com.fu.conversation;

import com.fu.conversation.config.ConversationModuleTestConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Test-only bootstrap for @SpringBootTest integration tests in the conversation module.
 *
 * Mirrors UserTestApplication: full scan over com.fu, excludes only the
 * @WebMvcTest-specific config. @MockBean EmailService in each test class
 * prevents EmailServiceImpl from being instantiated (same pattern as user module).
 *
 * @EntityScan(basePackages="com.fu") is required because this class lives in
 * com.fu.conversation — without it, Hibernate would not scan com.fu.course.entity.*
 * and VocabularyRepository would fail with "Not a managed type".
 */
@SpringBootApplication
@EntityScan(basePackages = "com.fu")
@ComponentScan(
        basePackages = "com.fu",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = ConversationModuleTestConfig.class
        )
)
public class ConversationTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConversationTestApplication.class, args);
    }
}
