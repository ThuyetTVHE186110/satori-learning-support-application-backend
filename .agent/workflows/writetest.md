---
description: Generate high-quality HTTP-layer integration tests with strong assertions verifying data transport mapping to ISTQB standards.
---

# Write Integration Test Workflow

You will help the user construct a high-quality Spring Boot Integration Test compliant exactly with the Satori standardized Integration Test Strategy. 

**DO NOT duplicate unit tests that check edge-case computations.** The goal is strictly executing **Functional/Thread Component Integration Testing** validating `Request -> Interceptors/Security -> Controller -> Service -> Persistence -> Database`. 

1. **Focus strictly on Implementation**: The user will ALWAYS provide the exact list of test cases (scenarios, expected results) upfront. **DO NOT** suggest adding, removing, or changing the test cases, and **DO NOT** decide what needs to be tested. Your ONLY job is to write the implementation for the exact test cases provided by the user using the standardized testing rules.
2. **Review Environment**: Inspect the primary associated classes: the Controller, matching Service, designated Request + Response DTOs, and underlying Repository.
3. **Skeleton Draft**: Structure the main integration class and enforce these class annotations:
   ```java
   @SpringBootTest(classes = com.fu.StartApplication.class)
   @AutoConfigureMockMvc
   @ActiveProfiles("test")
   ```
4. **Identify Inter-system Dependencies to Mock**: Detect what components reside external to Satori's intrinsic boundaries. Add `@MockBean` appropriately to (e.g., `EmailService`, `CloudinaryService`, `RoleplayAIService`, generic external API clients). Reset each using `Mockito.reset()` strictly in the `@BeforeEach` hook.
5. **Implement Standard Helpers**: Generate conventional helpers necessary for the workflow. At a minimum establish `loginAndGetToken()` for standard authentication, and `saveEntity()` factories for domain configuration. 
6. **Implement Strict Lifecycle Hooks**:
   - Establish `@BeforeEach` AND `@AfterEach`. Ensure you call `clearAllData()` universally inside both.
   - For `clearAllData()`, repository deletions **MUST** rigidly obey standard Foreign Key dependency drops (purge children references fully prior to dropping parents/users). 
   - Alternatively, utilize `@Sql(scripts = "/sql/truncate-all-tables.sql")` execution strategies if dependencies are heavily expansive.
7. **Organize Test Cases (`@Nested`)**: Encase distinct operations within `@Nested` groupings (e.g., `@Nested class CreateClass`, `@Nested class UpdateClass`).
8. **Enforce Two-Point Assertion Checks**: Ensure assertions are exhaustive on the boundaries. Verify the exact HTTP payload returned using `MockMvc` AND explicitly retrieve objects from the repository logic to verify matching database state representations. 
9. **Follow Deterministic AI Rules**: Ensure tests are strictly deterministic. When mocking LLM or Audio Processing AI service responses, rigorously inject hardcoded fake DTO responses matching the format the downstream app expects.
