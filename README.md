# ResilientPay backend

Private source component of Project 001 — ResilientPay Reversal Orchestrator,
by Daniel Jr Nkulu / Shape It Solutions Pty Ltd.

Java 25, Spring Boot 4.1.1, Maven Wrapper, explicit reversal workflow and
recovery semantics. Includes the domain, application, service and synthetic
switch modules, API/event contracts, and database materials. Synthetic data
only; no production payments, SLA, certification or regulatory approval claim.

## Build and run

Use the sibling-repository assembly described in
[resilientpay_infrastructure](https://github.com/danmjrn/resilientpay_infrastructure/blob/main/publication/WORKSPACE.md).
Original path prefixes are retained intentionally: tests and security tooling
share contracts, scripts, dashboards and evidence with other components.
Run the original documented commands from the assembled workspace, not from a
partially cloned repository.

## Publication provenance

Source release: `787e9999df939191f1723b8de0f3613ea0cc1061`,
original local tag `project-001-v0.1.0`. This repository retains the five
path-relevant source commits, followed by publication changes. Filtering changes
commit identifiers; it does not replace or rewrite the original 19-commit
repository. The original ZIP is unchanged.

[Release documentation](https://github.com/danmjrn/resilientpay_documentation)
contains the measured results and their limitations. The approved security
exception SEC-EX-001 expires no later than 2026-11-19T00:00:00Z.

## Configuration

No actual `.env` files or private identities belong here. Shared encrypted demo
configuration is owned by
[application_config](https://github.com/danmjrn/application_config/tree/main/apps/resilientpay).
The infrastructure renderer requires an active, validated local bundle; the
initial encrypted bootstrap bundle is deliberately not usable credentials.
CI never decrypts application secrets.

The licence decision remains
[explicitly reserved](https://github.com/danmjrn/resilientpay_documentation/blob/main/LICENSE-DECISION.md).
