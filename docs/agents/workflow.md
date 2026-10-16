# Workflow selection

Use one primary workflow per task. A skill explicitly invoked by the user is
the primary workflow; follow its stages and the supporting skills it calls for.
With no explicit selection, choose one suitable workflow and name it.

Use other skills as references for testing, design vocabulary or writing when
needed. Resolve overlapping planning, approval and handoff stages through the
primary workflow rather than starting a second workflow. Switch workflows when
the user requests it or the primary workflow hands off to another.

Carry approved scope, design decisions, constraints and test seams into the next
phase. Count an existing approval for the same decision; ask about unresolved
choices or material changes. Complete the primary workflow's remaining gates
before proceeding with the authorized work.

Use the tracker and domain documentation locations configured in `CLAUDE.md`
and `docs/agents/`; these take precedence over a skill family's default artifact
locations.
