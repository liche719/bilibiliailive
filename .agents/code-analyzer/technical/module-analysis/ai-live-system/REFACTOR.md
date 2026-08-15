---
title: "Refactoring Recommendations: AI Live System"
date: "2026-08-10"
updated: "2026-08-10"
project: "bilibili-ai-live"
type: "refactor-plan"
status: "active"
version: "1.0"
tags: ["bilibili-ai-live", "refactor-plan", "module-analysis", "ai-live-system"]
changelog:
  - version: "1.0"
    date: "2026-08-10"
    changes: ["Recorded remaining post-audit technical debt"]
related: ["[[REPORT]]"]
---

## Code Smells

| ID | Issue | Severity | Location | Description |
|---|---|---|---|---|
| D-001 | Large lifecycle component | Medium | `OfficialBilibiliLiveEventConnector` | Session, socket, heartbeat, retry, parsing dispatch, and executor ownership remain in one class |
| D-002 | External connector test seam | Medium | Bilibili HTTP/WebSocket integration | Policy logic is tested, but the JDK clients are constructed internally and full lifecycle simulation requires integration tests |
| D-003 | Minimal moderation policy | Medium | `KeywordModerationService` | The current local keyword list is intentionally small and is not a production-grade moderation system |

## Recommendations

| Issue | Recommendation | Priority | Impact | Effort |
|---|---|---|---|---|
| D-002 | Inject HTTP/WebSocket client adapters and add a deterministic fake Bilibili lifecycle test | High | High | Medium |
| D-001 | Extract session lifecycle and socket watchdog only when connector behavior next changes | Medium | Medium | Medium |
| D-003 | Add a configurable moderation provider before exposing the system beyond local personal use | Medium | High | Medium |

## Implementation Plan

1. Introduce client adapter interfaces without changing controller or ingress contracts.
2. Test auth timeout, silent-socket reconnect, session-end rebuild, and operator disconnect races.
3. Extract lifecycle state transitions after those characterization tests exist.
4. Keep current fail-closed Redis admission and local-only server binding unless product requirements change.

## Impact Analysis

| Risk | Probability | Severity | Mitigation |
|---|---|---|---|
| Reconnect regression during extraction | Medium | High | Characterization tests before structural changes |
| Overengineering a single-user MVP | Medium | Medium | Defer D-001 until lifecycle behavior changes again |
| Unsafe public deployment | Medium | High | Preserve loopback binding; add auth and stronger moderation first |

## Testing Strategy

- Keep the current unit suite and Testcontainers application-context test.
- Add fake-client lifecycle tests before refactoring the connector.
- Validate with one real Bilibili live session after Docker and official credentials are available.

## Referencias

- [[REPORT]] — Current system architecture and audit results.

