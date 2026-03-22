---
name: traceapiflow
description: Trace execution flow of a Spring Boot API.

---

Instructions:
1. Locate Controller (entry point)
2. Identify endpoint and request DTO
3. Trace Service calls step-by-step
4. Identify:
    - Business logic
    - DTO transformations
    - DB interactions (Repository)
    - Redis usage
    - External API calls (OpenAI)
5. Do not skip intermediate methods
6. Ask for missing files if needed

Output:
- Step-by-step flow
- Class + method names
- Key logic highlighted