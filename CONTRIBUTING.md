# Contributing to OutageWatch

Thanks for your interest in OutageWatch. It's a free power-outage alerts app for
PG&E territory in Northern and Central California, with no account required.
Contributions are welcome, whether that's a bug report, a fix, a test, or a
feature.

This guide covers how to get set up, run the tests and linters, and open a pull
request.

## Repo layout

The repo has two main parts:

- `backend/` - Python 3.12 FastAPI service plus the polling watcher engine that
  runs on Google Cloud Run. A poller reads PG&E's public ArcGIS outage feed
  every 5 minutes, diffs it, matches changes to subscriptions, and sends push
  notifications via FCM. Dependencies are managed with `uv`.
- `mobile/` - Kotlin Compose Multiplatform app for Android and iOS, sharing one
  UI codebase. Android code assembles from `mobile/androidApp`, shared code
  lives in `mobile/shared`, and the iOS app builds from `mobile/iosApp`.

## Prerequisites

- For the backend: Python 3.12 and [`uv`](https://github.com/astral-sh/uv).
- For Android: a recent JDK and the Android SDK (the Gradle wrapper handles the
  rest).
- For iOS: macOS with Xcode.

## Backend setup

From the repo root:

```sh
cd backend
uv sync --extra server
```

Run the tests and linter before you push:

```sh
uv run pytest
uv run ruff check .
```

Both need to pass. CI also runs `pip-audit`, so keep an eye on dependency
advisories if you touch `pyproject.toml`.

## Mobile setup

Android unit tests and a debug build:

```sh
cd mobile
./gradlew :shared:testAndroidHostTest
./gradlew :androidApp:assembleDebug
```

The iOS app builds from `mobile/iosApp` in Xcode on macOS. There's no CI for iOS,
so if you change shared code, please build the iOS app locally when you can.

## Pull request flow

1. Create a branch off `main` for your change.
2. Make your change, and add or update tests where it makes sense.
3. Run the relevant tests and linters locally (see above).
4. Open a pull request against `main`. Fill out the PR template so reviewers know
   what changed and how you tested it.
5. CI runs on every PR: the backend job (ruff, pytest, pip-audit) and the Android
   job (unit tests and `assembleDebug`). CI must pass before a PR is merged.

Small, focused PRs are easier to review and get merged faster. If you're planning
a larger change, it's worth opening an issue first to talk it through.

## Commit style

Keep commits focused and write a clear message. A short summary line in the
imperative mood works well, for example "Add ZIP validation to subscribe
endpoint". Add a body if the change needs explanation. Group related work into a
single commit rather than a string of "fix typo" commits where you can.

## Filing issues

Bugs and feature ideas go in
[GitHub Issues](https://github.com/nicglazkov/outagewatch/issues). There are
issue forms for bug reports and feature requests, so please use those. One note
worth remembering: PG&E's public feed can lag behind real conditions, so a
"missing" or "stale" outage isn't always a bug in the app.

## A note on style

The project voice is understated and concrete. In docs and user-facing text,
please avoid em dashes and en dashes; use commas, periods, or the word "to"
instead.

## License

By contributing, you agree that your contributions are licensed under the MIT
License, the same license that covers this project.
