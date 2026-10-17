# AGENTS.md

Guidance for coding agents working in this repository.

## Project

**DSH Mobile** — native Android client for the DeepSeek Harness. Kotlin + Jetpack Compose,
Android 8.0+ (minSdk 26, target/compileSdk 35, JDK 17). It is a **remote client**: the harness runs
on a host (desktop, LAN server, or Termux on the same phone) and owns all state. The app talks the
DSH wire protocol over HTTP plus a WebSocket downlink.

This repository **forks `sorsama/deepseek-harness-mobile`** and is intended for downstream work.
Treat it as a separate product: upstream is a source of changes, not an authority over this tree.

## Layout

| Path | Contents |
|------|----------|
| `core/` | Pure JVM, no Android dependencies: wire DTOs, RPC client, downlink, reconnect, event folding |
| `app/` | Compose UI, connection and discovery, foreground service, notifications, 11-language i18n |
| `mock-harness/` | Ktor mock of the harness `/api` server |
| `conformance/` | End-to-end tests against a real harness checkout |
| `harness/` | Host-side setup: LAN patch and Termux companion |
| `tools/capture/` | Node script that records live harness traffic into JSON fixtures |

## Sync with upstream

- `origin` is our fork; `upstream` is `sorsama/deepseek-harness-mobile`.
- Integrate upstream with `git fetch upstream && git rebase upstream/main`.
- Never push to `upstream`, and never rewrite `main` once it has been pushed.

## Remote operations

**Agent autonomy ends at the remote boundary.**

- Local commits are autonomous: commit whenever a logical change is complete, without asking.
- Anything that affects a remote requires explicit user approval first — `git push`,
  merging or opening pull requests, GitHub comments, releases, tags.

## Commits

- Commit at the end of each complete logical change; do not accumulate unrelated edits.
- Match upstream style: English, imperative mood, sentence case, no trailing period, no
  conventional-commit prefixes. Example: `Show tool-result images from their attachments`.
- Never add AI attribution trailers unless the user asks for one.
- Never commit or print secrets: `keystore.properties`, `*.jks`, `*.keystore`.

## Code knowledge base

`docs/` is written for **human contributors** (protocol, architecture, security, compatibility)
and is the correct reference for human-facing explanation.

Agent-facing index and conventions live under `docs/agent/`. For this fork that layer is
**not yet built** — until it exists, read `docs/ARCHITECTURE.md`, `docs/PROTOCOL.md`, and
`docs/COMPATIBILITY.md` directly.

When the index is introduced, it follows the same rules as the layered documentation:

- It exists to **route** an agent to one or two real files, then the agent reads those files.
  Its value equals the source reading it saves; it does not restate source content.
- Own only what source cannot express: cross-module boundaries, ordering, contracts, tradeoffs,
  and live prohibitions. Never list fields, copy signatures, or paste readable code.
- Describe the current state only. No migration narrative, no examples, no line numbers.
- Cite files as repository-relative paths with a symbol name, e.g.
  `core/src/main/kotlin/com/labteto/dshmobile/core/wire/dto/SkillsDtos.kt::SkillEntry`.
- One fact lives in exactly one document; cross-reference with relative links.
- Update the affected document in the same change as the code. Prefer folding new facts into
  existing prose over adding sections; keep every document the same length or shorter.

## Commands

Requires JDK 17+ and Android SDK 35. There is no root `package.json`; Gradle is the only entry point.

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :core:test                  # pure-JVM wire and folding tests
./gradlew :app:testDebugUnitTest      # app unit tests
./gradlew :app:lintDebug              # lint
./gradlew :core:test :mock-harness:test   # fast loop, no Android SDK device needed
```

Real-host conformance needs a harness checkout and is driven by
`DSH_HARNESS_SRC=<path> ./gradlew :conformance:test`. CI pins the harness commit; see
`.github/workflows/ci.yml`. Prefer the `:core:test` loop while changing protocol or DTO code.

## Conventions

- The wire layer parses leniently: unknown keys, events, and tool cards must degrade, never crash.
- UI strings belong in `values*/strings.xml` — never hardcode user-visible text.
- Keep `:core` free of Android imports; it must stay testable on a plain JVM.
- The app both invokes and projects skills and agent presets, but defines neither; the catalog is
  the host's. `skill.list` is the only skills RPC, and invocation is a plain `session.prompt`
  whose leading `/name` token the host recognizes.
- `dshVersionName` (default `0.16.0`) drives `versionCode` by derivation. A version that does not
  raise `versionCode` leaves Android unable to upgrade an installed build.
- Match `CONTRIBUTING.md` conventions for human-facing contribution rules.
