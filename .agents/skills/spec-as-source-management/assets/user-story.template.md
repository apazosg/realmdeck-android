---
id: <US-ID>
description: "<Brief summary for agent discovery>"
state: draft  # draft | ready | dirty | deprecated
---

# <US-ID>: <Title>

## As a
<role>

## I want
<capability>

## so that
<business value>

---

## Use Cases

- **<US-ID>-UC-01**: <description>
- **<US-ID>-UC-02**: <description>

---

## Acceptance Criteria

- **<US-ID>-AC-01** (UC-01): <criterion — observable, testable>
- **<US-ID>-AC-02** (UC-01): <criterion>
- **<US-ID>-AC-03** (UC-02): <criterion>

---

## Edge Cases

- <edge case description>
- <edge case description>

---

## Functional Requirements

Derive from UC/AC using EARS syntax (see "EARS Patterns for Functional Requirements" in
the skill).

### <US-ID>-FR-<NNN>
- **Derives from:** <US-ID>-AC-<NN>
- **Pattern:** <Ubiquitous | Event-driven | State-driven | Optional | Complex>

<EARS statement>

---

## Non-Functional Requirements

Local NFRs that apply only to this user story (system-wide NFRs go in SYSTEM.md):

- **<US-ID>-NFR-001**: <measurable constraint — e.g., "Response time < 500ms">
- **<US-ID>-NFR-002**: <constraint>

---

## Test Cases

Derive from FR using Gherkin format (see "Gherkin Guide for Test Cases" in the skill).

```gherkin
# <US-ID>-TC-<NNN> (validates <US-ID>-FR-<NNN>)
Scenario: <descriptive name>
  Given <context>
  When <action>
  Then <expected result>
```

---

## Assumptions

- `[ASSUMPTION]` <decision made without explicit user confirmation>
- <confirmed assumption>

## Open Questions

- <unresolved ambiguity or pending decision>

---

## Example

````markdown
---
id: US-005
description: "User resets password via email"
state: ready
---

# US-005: User resets password

## As a
registered user

## I want
to reset my password via email

## so that
I can regain access to my account if I forget my credentials

---

## Use Cases

- **US-005-UC-01**: User requests a password reset link
- **US-005-UC-02**: User sets a new password via the reset link

---

## Acceptance Criteria

- **US-005-AC-01** (UC-01): System sends a reset email within 60 seconds
- **US-005-AC-02** (UC-01): Reset link expires after 24 hours
- **US-005-AC-03** (UC-02): New password must meet complexity requirements
- **US-005-AC-04** (UC-02): Previous password cannot be reused

---

## Edge Cases

- User requests multiple reset links before using any
- Reset link is used after expiration

---

## Functional Requirements

### US-005-FR-001
- **Derives from:** US-005-AC-01
- **Pattern:** Event-driven

WHEN the user submits a password reset request,
THE SYSTEM SHALL generate a unique token, store it hashed, and send a reset link
to the registered email address within 60 seconds.

### US-005-FR-002
- **Derives from:** US-005-AC-02
- **Pattern:** Complex

WHEN the user opens a reset link, IF more than 24 hours have passed since generation,
THE SYSTEM SHALL reject the request and display an expiration message.

### US-005-FR-003
- **Derives from:** US-005-AC-04
- **Pattern:** Optional

IF the new password matches the current password,
THE SYSTEM SHALL reject the change and display an error message.

---

## Non-Functional Requirements

- **US-005-NFR-001**: Reset email delivered within 60 seconds
- **US-005-NFR-002**: Reset token stored hashed, not in plaintext

---

## Test Cases

```gherkin
Feature: Password Reset (US-005)

  Background:
    Given a registered user with email "user@example.com"

  # US-005-TC-001 (validates US-005-FR-001)
  Scenario: Successful reset link request
    Given the user is on the login page
    When the user requests a password reset for "user@example.com"
    Then the system sends a reset email to "user@example.com"
    And the email is delivered within 60 seconds

  # US-005-TC-002 (validates US-005-FR-002)
  Scenario: Expired reset link
    Given the user received a reset link 25 hours ago
    When the user opens the reset link
    Then the system rejects the request
    And displays the message "This link has expired"

  # US-005-TC-003 (validates US-005-FR-003)
  Scenario: Reuse of current password
    Given the user has opened a valid reset link
    When the user enters their current password as the new password
    Then the system rejects the change
    And displays the message "New password must be different from the current one"
```

---

## Assumptions

- `[ASSUMPTION]` Email is the only reset channel (no SMS)

## Open Questions

- Should the system invalidate active sessions on password reset?
````