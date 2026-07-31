# Security Policy

## Supported Version

Security fixes target the latest release and the default branch.

## Reporting

Security vulnerabilities must be reported through GitHub Private Vulnerability Reporting under the repository Security tab. Public issues must not contain credentials, capability URLs, personal data, exploit details, or vulnerable commit identifiers.

Reports should include the affected component, reproduction conditions, impact, and a minimal proof of concept. Receipt is normally acknowledged within seven days.

## Security Boundaries

- Raw learning datasets and student-level derived records remain outside Git and container images.
- Secrets are supplied through untracked environment files or encrypted runtime configuration.
- Public application traffic enters through Caddy; database, Redis, model service, and Actuator endpoints remain private.
- Public responses omit model trace, calibration, and internal evidence fields.
