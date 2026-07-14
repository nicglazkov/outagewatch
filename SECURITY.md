# Security Policy

OutageWatch is a free, solo-maintained project. Security reports are taken
seriously, and this document explains what's covered and how to report a problem.

## Supported versions

Security fixes land in the latest release. Only the latest release is supported.
If you're running an older build, please update before reporting an issue, since
it may already be fixed.

## Reporting a vulnerability

Please do not open a public issue for a security problem that could put users at
risk before it's fixed.

There are two ways to report:

1. **GitHub's private vulnerability reporting (preferred).** Go to the
   [Security tab](https://github.com/nicglazkov/outagewatch/security) of the repo
   and use "Report a vulnerability" to open a private security advisory. This
   keeps the details confidential while the issue is being worked on.
2. **A GitHub issue**, if the problem is low risk or already public. Open one at
   [github.com/nicglazkov/outagewatch/issues](https://github.com/nicglazkov/outagewatch/issues).

When in doubt, use the private advisory channel.

There is no contact email for this project. Please use the channels above.

## What to include

A good report usually has:

- What the issue is and why it's a problem.
- Steps to reproduce, or a proof of concept.
- The affected component (API, app, or infrastructure) and the version or commit
  if you know it.
- Any suggested fix or mitigation you have in mind.

## Scope

In scope:

- **The API**, the FastAPI backend and watcher engine in `backend/` running on
  Google Cloud Run.
- **The app**, the Compose Multiplatform Android and iOS clients in `mobile/`.
- **The infrastructure** that runs the service, including how secrets and push
  credentials are handled.

Out of scope:

- PG&E's own systems and their public ArcGIS outage feed. OutageWatch only reads
  that feed; issues with PG&E's data or services should go to PG&E.
- Third-party services such as Firebase Cloud Messaging or Google Cloud Run
  themselves. Report those to the respective vendor.
- Reports that boil down to PG&E data being stale or delayed. That's a known
  limitation of the upstream feed, not a security issue.

## What to expect

This is a solo-maintained project, so response times are best effort rather than
a guaranteed SLA. A realistic expectation is an initial acknowledgement within
about a week. Confirmed issues are prioritized by severity, and once a fix ships
in a release, credit is offered to reporters who want it.

Thanks for helping keep OutageWatch and its users safe.
