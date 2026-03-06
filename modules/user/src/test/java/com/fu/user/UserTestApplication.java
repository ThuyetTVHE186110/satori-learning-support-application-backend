package com.fu.user;

import com.fu.user.config.UserModuleTestConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Test-only bootstrap for @SpringBootTest integration tests in the user module.
 *
 * Uses a full component scan over com.fu but explicitly excludes
 * UserModuleTestConfig, which disables DataSource/JPA/Redis auto-configuration
 * for @WebMvcTest unit tests and would break real DB integration tests.
 *
 * NOTE: @EntityScan(basePackages="com.fu") is required because without it,
 * Hibernate would not scan com.fu.clazz.entity.* and JPQL queries in
 * UserRepository referencing ClassMember would fail with "Not a managed type".
 */
@SpringBootApplication
@EntityScan(basePackages = "com.fu")
@ComponentScan(basePackages = "com.fu", excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = UserModuleTestConfig.class))
public class UserTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserTestApplication.class, args);
    }
}
