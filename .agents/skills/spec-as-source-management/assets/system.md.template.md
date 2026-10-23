---
id: SYSTEM
description: "Global constraints, technology stack, and system-wide NFRs"
state: draft  # draft | ready | dirty | deprecated
---

# SYSTEM SPECIFICATION

This document defines constraints that apply to the **entire system**. If a constraint
applies only to a specific feature, define it as an NFR within that feature's User Story
instead.

**Rule of thumb:** ask "does this affect every feature?" If yes, it belongs here.
If it only affects one feature, it belongs in that US.

---

## Architecture Constraints

Define high-level architecture rules and patterns:

- <e.g., Microservices architecture with API gateway>
- <e.g., Event-driven communication between services>
- <e.g., Monorepo with shared library packages>

---

## Technology Stack

| Layer | Technology |
|---|---|
| Backend | <e.g., Node.js + Express> |
| Frontend | <e.g., React 18 + TypeScript> |
| Database | <e.g., PostgreSQL 16> |
| Cache | <e.g., Redis> |
| Auth | <e.g., OAuth 2.0 + JWT> |
| Infrastructure | <e.g., AWS ECS + Terraform> |

---

## Global Non-Functional Requirements

These apply system-wide. Use EARS syntax and assign `SYS-NFR-<NNN>` IDs:

### SYS-NFR-001: Response time
THE SYSTEM SHALL respond to API requests within 500ms at the 95th percentile.

### SYS-NFR-002: Availability
THE SYSTEM SHALL maintain 99.9% uptime measured monthly.

### SYS-NFR-003: Security
THE SYSTEM SHALL encrypt all data in transit using TLS 1.2 or higher.

> Replace the examples above with your actual system constraints.

---

## Cross-Cutting Concerns

Define behaviors that span multiple features:

- **Logging:** <e.g., structured JSON logs, centralized in CloudWatch>
- **Authentication:** <e.g., all endpoints require Bearer token except /health>
- **Observability:** <e.g., OpenTelemetry traces on all service boundaries>
- **Error handling:** <e.g., standardized error response format with correlation ID>

---

## Assumptions

- `[ASSUMPTION]` <system-level assumption not yet confirmed>

## Notes

Keep this document aligned with evolving system design. When a global constraint changes,
check all US-level FR/NFR for impact and mark affected documents as `dirty`.