---
name: task-planning
description: >
  Applies a structured plan-before-execute workflow when a task needs a material
  decision, has consequential risk, or the user explicitly asks for a plan. Use it for
  unresolved important choices; contracts, security, data, deployment or migrations;
  irreversible changes; or coordination between independent components or repositories.
  Also use when the user says "planifica", "haz un plan", "antes de hacer nada",
  "¿cuál es el plan?", "escribe un plan", "necesito un plan", or "qué cambios harías".
  DO NOT USE merely because a task touches several files, has linear steps, or syncs mirrors.
  NEVER execute changes before the plan is written to disk and the user approves.
---

# Task Planning

## Purpose

Guide an AI agent to plan before acting on any task with meaningful complexity or
ambiguity. The output of this skill is a plan document on disk — not changes to files.
Changes happen only after the user approves the plan.

This workflow is domain-agnostic. It applies to documentation tasks, code changes,
refactors, audits, or any multi-step operation.

---

## When to Use Planning

Apply this skill when **any** of the following is true:

| Signal | Example |
|---|---|
| **Explicit user request** | The user asks for a plan before execution |
| **Material decision or uncertainty** | A choice changes scope, architecture or user-visible behavior |
| **Consequential change** | Public contract, auth/security, schema/data, deployment or external integration |
| **Migration or irreversible operation** | Deletion, history rewrite or a change costly to undo |
| **Independent coordination** | Multiple components or repositories have coupled order, contracts or rollout |

Skip planning when all of the following hold:

- The target and expected behavior are already clear.
- The change is bounded and reversible, without a contract or material-risk decision.
- Multiple files are only mechanical consumers, generated mirrors or linear edits.
- A short answer, review, lookup or one cohesive implementation can be validated directly.
- A reported error or inconsistency has an evidence-backed cause and a bounded correction
  already dictated by the approved spec.

If diagnosis reveals a contract, security, data, deployment, migration or coordination decision, it is no longer a known incident and the plan criteria apply.
---

## Workflow

### Phase A — Assess and Plan

1. Read all relevant context (agents.md, affected files, skill bodies as needed).
2. Identify: what needs to change, what the risks are, what decisions are needed.
3. List any open questions that require user input before proceeding.
4. Draft the plan document (see Plan Document Format below).

**STOP. Write the plan file to disk. Do not change any file until the user approves.**

Present the plan path and request approval. In that same message, include a concise,
self-contained summary of the scope, key decisions or findings, planned actions, and
open questions, so the user can decide without opening the plan. If there are open
questions, list them explicitly and wait for answers before finalising the plan.

### Phase B — Execute

1. Re-read the plan from disk.
2. Execute items in plan order. Mark each checkbox after completion.
3. If execution reveals new information that affects other items, **STOP**, add it to
   `## Open Issues`, and surface it to the user before continuing.
4. After all items are done, set `state: done` in the plan frontmatter.

---

## Plan Document Format

All planning documents follow the same lifecycle and storage convention, regardless of
their topic (skill audit, refactor, documentation restructure, MVP scope, etc.).

### Storage paths

| State | Path |
|---|---|
| Active (being worked on) | `.agents/planning/<topic>-plan.md` |
| Completed | `.agents/planning/done/<topic>-plan.md` |

Choose a `<topic>` that is short and descriptive: `skill-audit`, `roadmap`, `mvp-scope`,
`migration`, `restructure-planning-skill`, etc.

**Move to `done/` when `state: done`** — do not delete. Completed plans are the
project's decision log.

### Required frontmatter

```yaml
---
id: <topic>-YYYY-MM-DD
state: draft   # draft | approved | in-progress | done
date: YYYY-MM-DD
---
```

Read `assets/plan-template.md` when creating a new plan document — it contains the pure structure to copy.
Read `assets/plan-example.md` if you need a filled-in reference (skill audit scenario).

---

## Agent Behavior Extensions

The universal agent behavior principles in the `Agent Behavior` section of `agents.md` apply to all tasks.

The following rules are specific to the task-planning workflow:

### Never merge plan and execution into one step

Writing a plan and executing it must be separate actions separated by a user STOP gate.
Do not draft a plan and immediately start modifying files in the same response.

### Surface ambiguity early

If the assessment in Phase A reveals open questions, list them in the plan under
`## Open Issues` before presenting it to the user. Do not silently resolve ambiguity
by picking the most likely interpretation.

### One plan per operation

Do not accumulate multiple concurrent operations in one plan unless they are logically
inseparable. If a user request covers two distinct topics, consider whether two separate
plans are clearer.

---

## Checklist

- [ ] Assess: does this task involve an explicit request, material decision/risk, migration or independent coordination?
- [ ] Read all relevant context before writing the plan
- [ ] List open questions in `## Open Issues` — do not silently resolve ambiguity
- [ ] Write plan file to disk at `.agents/planning/<topic>-plan.md`
- [ ] **STOP — present the plan path, a self-contained summary, and wait for approval**
- [ ] Re-read plan from disk before executing
- [ ] Execute items in plan order; mark each checkbox after completion
- [ ] If new information changes the scope, STOP and surface to user
- [ ] Set `state: done` and move plan to `.agents/planning/done/` when complete
