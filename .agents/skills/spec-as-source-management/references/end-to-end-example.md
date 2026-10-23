# End-to-End Example: US-010

A worked example showing the full spec-as-source flow for a single feature.

---

**File: `US-010.md`**

---
id: US-010
description: "User exports monthly report as PDF from the dashboard"
state: ready
---

# US-010: User exports report

## As a
manager
## I want
to export a monthly report as PDF
## so that
I can share it with stakeholders offline

---

## Use Cases
- **US-010-UC-01**: User triggers export from the dashboard
- **US-010-UC-02**: System generates PDF and notifies user

## Acceptance Criteria
- **US-010-AC-01** (UC-01): Export is available only for completed reports
- **US-010-AC-02** (UC-02): PDF contains all sections visible in the dashboard

---

## Functional Requirements

### US-010-FR-001
- **Derives from:** US-010-AC-01
- **Pattern:** Complex

WHEN the user requests a report export, IF the report state is not "completed",
THE SYSTEM SHALL reject the request and display an error message.

### US-010-FR-002
- **Derives from:** US-010-AC-02
- **Pattern:** Event-driven

WHEN the user requests a report export,
THE SYSTEM SHALL generate a PDF containing all sections visible in the dashboard.

---

## Test Cases

```gherkin
# US-010-TC-001 (validates US-010-FR-001)
Scenario: Reject export of incomplete report
  Given a report with state "in-progress"
  When the user requests a PDF export
  Then the system rejects the request
  And displays the message "Only completed reports can be exported"

# US-010-TC-002 (validates US-010-FR-002)
Scenario: Successful export of completed report
  Given a report with state "completed" containing sections "Summary" and "Details"
  When the user requests a PDF export
  Then the system generates a PDF
  And the PDF contains sections "Summary" and "Details"
```
