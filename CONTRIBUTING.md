# Contributing

## Development Contract

EduTwin accepts focused changes that preserve role authorization, public response sanitization, data licensing boundaries, and deterministic verification.

## Setup

```powershell
npm --prefix frontend ci
./backend/mvnw -f backend/pom.xml test
```

The browser showcase starts with:

```powershell
npm --prefix frontend run dev:showcase
```

The compact full stack starts with:

```powershell
docker compose -f infra/compose/showcase.yaml up --build --wait
```

## Change Requirements

- Public API changes require matching OpenAPI updates and contract verification.
- Event changes require matching AsyncAPI and event-semantics updates.
- Database changes require forward-only Flyway migrations.
- Frontend changes require desktop and mobile checks.
- Modeling changes require deterministic seeds, frozen configuration, and metric provenance.
- Raw or student-level ASSISTments/OULAD data must never enter Git, images, logs, or releases.

## Pull Requests

Pull requests must describe the behavior change, affected roles, verification commands, and data/security impact. Generated files and unrelated formatting changes remain outside the change set.

Commit messages follow Conventional Commits, for example `feat: add showcase role switching` or `fix: preserve course authorization`.
