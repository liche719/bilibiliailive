# Technical References and Verification Rules

> Last checked: 2026-08-09 (Asia/Shanghai)
>
> This is a source register, not a replacement for current official documentation. Live-platform capabilities, application permissions, API schemas, and library versions can change. Re-check the relevant source immediately before implementing an integration.

## Source Hierarchy

1. The latest explicit user instruction and repository evidence.
2. The authenticated Bilibili Live Open Platform application's current console and official documentation.
3. Official vendor/framework documentation below.
4. Non-official sources only for local investigation, never as unlabelled production authority.

## Official Sources

| Area | Source | What it supports | Implementation rule |
|---|---|---|---|
| Bilibili Live Open Platform | [Open documentation](https://open-live.bilibili.com/document/) | Official documentation entry point | Verify current capabilities, event delivery, authentication, review status, and allowed actions there before coding the real adapter. Record a retrieval date and never record credentials. |
| Bilibili Live Open Platform | [Integration flow](https://open-live.bilibili.com/document/849b924b-b421-8586-3e5e-765a72ec3840) | Official onboarding/integration flow entry point | Do not infer SDK names, event names, callback formats, or access scope from this link alone. Record the exact detailed page used. |
| Java | [Oracle Java SE support roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html) | Java release and support-lifecycle context | Use Java 21 as the conservative LTS baseline unless the repository explicitly standardizes another supported LTS. |
| Spring Boot | [System requirements](https://docs.spring.io/spring-boot/system-requirements.html) | Java/build-tool compatibility for the current Spring Boot release | Resolve the actual Spring Boot version from this documentation and the generated build, rather than hard-coding a version from a planning document. |
| Maven | [Maven documentation](https://maven.apache.org/guides/) | Build lifecycle and dependency management | Commit the Maven Wrapper and keep dependency versions in one managed place. |
| Spring Framework | [WebFlux reference](https://docs.spring.io/spring-framework/reference/web/webflux.html) | `WebClient`/reactive HTTP client concepts | Using `WebClient` does not require converting an MVC/JPA application to end-to-end reactive programming. |
| Spring Security | [Reference documentation](https://docs.spring.io/spring-security/reference/) | Authentication and authorization building blocks | Keep the control panel private and model overlay access as a separate read-only authority. |
| Spring AI | [OpenAI chat reference](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html) | Spring AI's current OpenAI integration | Confirm that it exposes every required provider capability before choosing it over a direct provider SDK. |
| OpenAI | [Official text generation guide](https://developers.openai.com/api/docs/guides/text) | Current OpenAI text-generation API guidance | Check the current API model, endpoint, streaming, safety, and SDK requirements at implementation time. Local retrieval of this page may be access-restricted; the URL remains the authority. |
| React | [React documentation](https://react.dev/learn) | React application concepts and APIs | Follow the current supported React APIs; do not copy framework-specific assumptions into the backend contract. |
| Vite | [Vite guide](https://vite.dev/guide/) | Frontend development/build workflow | Pin the Vite version from the generated project and verify the Node.js requirement. |
| TanStack Query | [React overview](https://tanstack.com/query/latest/docs/framework/react/overview) | Server-state fetching, caching, and mutations | Keep server state in Query; reserve a local store for genuinely local UI state. |
| PostgreSQL | [Current documentation](https://www.postgresql.org/docs/current/) | PostgreSQL server and SQL documentation | Use supported database features and verify version-specific behavior against the deployed PostgreSQL release. |
| Redis | [Current documentation](https://redis.io/docs/latest/) | TTL, data structures, and operational guidance | Use Redis only for explicitly ephemeral/cache use unless a durable data model is deliberately designed. |
| Flyway | [Flyway documentation](https://documentation.red-gate.com/flyway) | Versioned database migration workflow | Every schema change must be a reviewed, forward migration; do not edit an applied migration. |
| OBS Studio | [Browser Source guide](https://obsproject.com/kb/browser-source) | OBS Browser Source configuration | Verify overlay behavior in the target OBS version; keep the tokenless overlay loopback-only, or add authentication before remote exposure. |
| Docker | [Compose documentation](https://docs.docker.com/compose/) | Docker Compose configuration and workflows | Keep local and initial production Compose configurations explicit and reproducible. |
| Testcontainers | [Java getting started](https://testcontainers.com/guides/getting-started-with-testcontainers-for-java/) | Real dependency integration testing | Use disposable PostgreSQL/Redis containers for integration tests rather than relying only on mocks. |
| OWASP | [Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html) | Secret handling principles | Never commit secrets or disclose them through logs, error output, documentation examples, or screenshots. |

## Non-Negotiable Verification Checklist for the Official Bilibili Adapter

Complete every row with a current official source before declaring a real integration done.

| Question | Evidence to record |
|---|---|
| Which app capability is approved? | App console status, retrieval date, and exact official document URL. |
| Which live-room binding/authorization is required? | Official documented setup steps and a sanitized test result. |
| How are events delivered? | Exact official SDK/API/documentation page, transport, lifecycle, and reconnect requirements. |
| What credentials are required and how do they expire/rotate? | Official documentation and deployment secret names, never secret values. |
| Which event types are actually available? | Official schema/event reference and a sanitized sample from the approved test path. |
| What actions may the app take? | Official permission scope and user-approved product behavior. |
| Can the app send a room reply? | Exact official outbound API/SDK contract, permission scope, request/response schema, rate limit, and a successful test; overlay output alone is not evidence. |
| How is a failure reported or retried? | Documented error semantics plus an integration test/result. |

## Research Limits

- The source register intentionally does not assert specific Bilibili event names, endpoint URLs, SDK package names, or permission scopes because these are app- and review-dependent.
- A community SDK, code sample, or reverse-engineered protocol can help investigate locally, but it is not a production source of truth unless the user expressly accepts that risk.
- Version numbers are intentionally not copied into the development prompt. They are fast-changing implementation details and must be pinned from the current, compatible official sources when the project is scaffolded.
