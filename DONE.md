# Java 11 → 21 Migration / Upgrade Revamp — Progress (DONE.md)

Branch: `demo-dev` · Source of truth: `design/registration/registration-upgrade.md` · Working spec: `design/registration/upgrade-implementation-spec.md`

> Status: **all toolchain-independent work is complete**. Remaining items are blocked on (a) the native-exe toolchain decision and (b) a PO clarification. **Nothing is committed yet.**

---

## 1. Overview

Re-architecture of the Registration Client self-upgrade so it can perform a **zero-touch Java 11 → 21 JRE swap** on operator machines (target release 1.3.0). The design introduces:

- a separate **`_launcher.jar`** (the new sole entry point) that orchestrates upgrades on every startup,
- native **`migration.exe` / `rollback.exe`** that perform the actual JRE swap outside the JVM,
- **dual `MANIFEST.MF`** (root = version/orchestration, `lib/` = per-file hashes) with detached `.sig` signatures,
- **`.artifacts/`** staging and **resumable** downloads.

The work is tracked as six areas, **T1–T6** (see §6 / §7).

---

## 2. Decisions

| # | Decision | Choice | Status |
|---|----------|--------|--------|
| 1 | Native-exe toolchain | TBD — must have **no JVM dependency** (Go prototype written then removed) | **REOPENED** |
| 2 | Manifest signing | Reuse `keystore.p12` + `provider.pem` as a **detached `MANIFEST.MF.sig`** | Locked |
| 3 | Shared code | Extract a Java-11 **`registration-launcher-common`** module | Locked |
| 4 | Platform scope | **Windows-only** | Locked |
| — | Resumable download | HTTP `Range` + `.part` staging + atomic move | Implemented |
| — | Java-11 build in 21 reactor | per-module `maven-compiler-plugin` `<release>11</release>` | Implemented |

---

## 3. What's Done

### 3.1 `registration-launcher-common` (new Java-11 module)
**What:** shared primitives used by both `_launcher.jar` (Java 11) and `registration-services` (Java 21).

| Class | Purpose |
|-------|---------|
| `ResumableDownloader` | HTTP `Range` resume, `.part` staging, atomic move, disk-space guard |
| `HashUtil` | SHA-256 → upper-case hex |
| `ManifestVerifier` | manifest version read/compare; per-file hash verification |
| `SignatureVerifier` | detached `SHA256withRSA` verify; load public key from X.509 cert |
| `ZipExtractor` | unzip with zip-slip path-traversal protection |

**How:** module compiled at `<release>11</release>` (via `combine.self="override"`), dependencies limited to slf4j + JUnit — **no client/services jars** — so the artefacts are safe to load under the Java 11 JRE. Tested with JUnit (no PowerMock) including an embedded `com.sun.net.httpserver` for the downloader. **17 tests.**

### 3.2 `registration-launcher` (new Java-11 module → `_launcher.jar`)
**What:** the new sole entry point and its startup orchestration.

| Class | Purpose |
|-------|---------|
| `Initialization` | entry point (`io.mosip.registration.controller.Initialization`); evaluates startup and branches |
| `JreVersionDetector` | parses `java.version` (11 / 11.0.3 / 21.0.3+9 / legacy 1.8.0_x) |
| `StartupEvaluator` | **step 2** decision: signature gate + version compare → action |
| `LibUpdater` | **step 5** JRE-21 lib-only update (download → verify → unzip → per-file verify) |
| `MigrationCleaner` + `NormalStartup` | **step 6** artefact cleanup + reflective launch of `ClientApplication` |
| `LauncherConfig` | resolves upgrade-server URLs from `mosip-application.properties` |
| `JreMigrationStager` | **step 3 preparation** (everything except launching the exe) |

**How:** `finalName=_launcher` so the jar sorts ahead of `registration-client.jar` on the classpath; `Main-Class` set to the same FQN as the legacy client entry so the existing `run.bat` resolves it. Normal startup uses **reflection** (`LauncherImpl.launchApplication`) so the Java-11 launcher carries no compile-time dependency on the Java-21 client. **31 tests.**

### 3.3 `registration-services` (existing module)
**What:** `SoftwareUpdateUtil.downloadResumable(...)` / `ensureSpace(...)` now **delegate** to `registration-launcher-common`, translating `IOException` → `RegBaseCheckedException` and supplying timeouts from `ApplicationContext`.

**How:** added a dependency on `registration-launcher-common`; the existing public API and behaviour are preserved. **20 `SoftwareUpdateUtilTest` still green.**

### 3.4 Documentation
- `design/registration/registration-upgrade.md` — authoritative design (from PR #763).
- `design/registration/upgrade-implementation-spec.md` — working companion: decisions, the **`migration.exe`/`rollback.exe` algorithm specs**, filesystem state model, and open items.
- `AGENTS.md` + `CLAUDE.md` (pointer) — agent/onboarding guide.

### 3.5 Security hardening (from code review — P0 fixes)
- **Verify-before-place + manifest allowlist.** `LibUpdater` now keeps the signature-verified manifest in memory, restores it on disk after extract (so `lib.zip`'s own `MANIFEST.MF` can't replace the verified one), and rejects any extracted file the manifest does not list (`ManifestVerifier.findUnexpectedFiles`).
- **JRE-migration branch is now verified.** `JreMigrationStager` stages the lib via the hardened `LibUpdater` (signature + per-file hash + allowlist) and integrity-checks `jre21.zip`/exes/`_launcher.jar` against the signature-verified **root** manifest before unzipping/copying (gated on the root manifest carrying their hashes — see open item 4).

### 3.6 Correctness hardening (from code review — P1 fixes)
- **Disk-space guard no longer skipped on unknown size.** `ResumableDownloader.ensureSpaceForWrite` warns + skips (instead of silently passing `0`) when `Content-Length` is unknown, and on a 200 restart it adds back the stale `.part` bytes that will be freed, so it neither under- nor over-rejects.
- **HTTP 416 validated, not trusted.** A 416 now finalizes the `.part` only if its size matches the server's `Content-Range` total; otherwise the stale `.part` is discarded and the download restarts fresh.
- **Unsupported JRE handled.** `StartupEvaluator` returns `MIGRATE_JRE` for 11, `UPDATE_LIB` for `>= 21`, and `ABORT_UNSUPPORTED_JRE` otherwise (e.g. a stale Java 8) — no longer staging Java-21 libs onto an incapable runtime.
- **Clear failure on a bad manifest.** `Initialization.requireVersion()` fails with a clear message when the root manifest lacks `Manifest-Version`, instead of an opaque NPE deep in URL building.
- **Robust timeout config.** `SoftwareUpdateUtil.getTimeout` falls back to the default on a non-numeric configured value instead of throwing an unchecked exception that bypasses the `REG-BUILD-005` contract.

### 3.7 UX hardening (from code review — P2 fix)
- **No false security alerts on download errors.** `LibUpdater.parseDownloadedManifest` detects an empty/unparseable/version-less downloaded manifest (e.g. a truncated body or an HTML error page) and fails it as a plain network/server error ("Update failed"), reserving the "signature invalid" security message for a well-formed manifest that genuinely fails verification.

### 3.8 Test summary
| Module | Tests |
|--------|-------|
| `registration-launcher-common` | 17 |
| `registration-launcher` | 32 (incl. negative tests: tampered `jre21.zip`, invalid/ malformed lib manifest, unsupported JRE) |
| `registration-services` (`SoftwareUpdateUtil`) | 20 (full services suite: 1034, 0 failures) |

All build at `<release>11</release>` inside the Java-21 Maven reactor.

---

## 4. How it was done (approach)

- **Decision-first, then build.** The four cross-cutting decisions (toolchain, signing, shared-code, platform) were locked before coding so the module structure wouldn't churn.
- **Incremental, always-green.** Each piece (resumable download → common primitives → launcher steps 2/5/6 → config → step-3 prep) was built and verified with a passing build before moving on.
- **Pure logic separated from side effects.** Decision logic (`StartupEvaluator`, `LibUpdater`, `JreMigrationStager`) is pure/testable; network, dialogs, and `System.exit` live in `Initialization`. This made the security-critical paths unit-testable.
- **Reuse over duplication.** The resumable-download code added on the services side was relocated into the shared module so both layers use one implementation.

---

## 5. Difficulties / notable findings

1. **JRE-swap constraint drove the toolchain.** `migration.exe` runs *while the JRE is being replaced*, so it must not depend on the JVM — ruling out plain-Java/Launch4j and pushing toward a native binary.
2. **Java-11 isolation.** `_launcher.jar` must never load Java-21 classes (`UnsupportedClassVersionError` risk). Handled by a dedicated `<release>11</release>` module with no client/services deps, and **reflective** normal startup.
3. **Hash compatibility.** Per-file hashes are written by kernel `HMACUtils2.digestAsPlainText`. Confirmed from kernel source that it is **SHA-256, upper-case hex**, and locked `HashUtil` to it with a known test vector — so the launcher's verification can't silently drift.
4. **Manifest signing is net-new.** Today the `MANIFEST.MF` is unsigned (jars are jar-signed + HMAC). The new model adds a detached signature over the manifest — same keys, new operation.
5. **Building Java 11 inside a Java 21 reactor.** Needed a per-module compiler override (`combine.self="override"` + `<release>11</release>`) so the parent's `21` settings don't apply.
6. **No Go toolchain in the authoring environment** — the native exes could be written but not built/tested here (and were subsequently removed, see §6).
7. **PowerShell quirk:** `mvn` `-D` args must be quoted (`"-Dgpg.skip=true"`) or PowerShell splits them on the dot.

---

## 6. Remaining / Blockers

### 6.1 Blocking chain (root cause)
```
Decision #1: native-exe toolchain (REOPENED)
   → blocks T4 (rollback.exe) → blocks T3 (migration.exe)
   → blocks T2 step-3 final "launch migration.exe" line → blocks T2 close
   → blocks T1 (.artifacts/ rewire) and T6 (cutover)
   T5 (build pipeline) needs T2 + T3 + T4
```

### 6.2 Blocked items
| Item | Blocked by | Why |
|------|-----------|-----|
| Final `launch migration.exe` line (step 3) | T3 | Can't call a binary that doesn't exist |
| **T3** `migration.exe` | Decision #1 + T4 | Toolchain undecided; migration auto-invokes rollback |
| **T4** `rollback.exe` | Decision #1 | Toolchain undecided |
| **T1** rewire downloads → `.artifacts/` | T2 (must close) | Flipping before the launcher consumes `.artifacts/` breaks live upgrade |
| **T5** build pipeline (`configure.sh`) | T2 + T3 + T4 | Must package/sign/host `_launcher.jar` + the two exes |
| **T6** remove client `main()` | T2 | Irreversible cutover; only safe once the launcher fully works |

### 6.3 Open confirmations (with PO / doc owner — expected EOD)
1. **lib.zip hash verification** — the doc's step 5 ("verify lib.zip hash vs a manifest entry") contradicts the dual-manifest model (lib.zip is in no manifest). Implemented the consistent reading: verify the manifest signature, then verify **each extracted file** against its per-file hash. *Needs confirmation.*
2. **lib.zip must be flat** — for `.TEMP/* → lib/` to land jars in `lib/`, `lib.zip` entries must be at the archive root; `configure.sh` currently zips with a `lib/` prefix. **T5 fix.**
3. **Process** — task to be split into one parent issue + sub-tasks (T1–T6); PO to get back by EOD.

### 6.4 Removed
- The **Go** implementation of `migration.exe`/`rollback.exe` (`registration/native/`) was written then removed; Decision #1 reopened. The **algorithm spec remains** (language-neutral) in `upgrade-implementation-spec.md`.

---

## 7. Task status

| Task | Area | Status |
|------|------|--------|
| T1 | `softwareUpdateHandler` changes | In progress — resumable done; `.artifacts/` rewire gated on T2 |
| T2 | `_launcher.jar` module | In progress — steps 2/3-prep/5/6 + Case C done; only the `launch migration.exe` line left |
| T3 | `migration.exe` | Pending — spec'd; toolchain TBD |
| T4 | `rollback.exe` | Pending — spec'd; toolchain TBD |
| T5 | build pipeline / `configure.sh` | Pending |
| T6 | remove client `main()` | Pending |

---

## 8. Build & run notes

- Build a single module (from `registration/`):
  ```
  mvn -pl <module> install "-Dgpg.skip=true" "-DskipTests=false" "-Dmaven.javadoc.skip=true"
  ```
  (Quote the `-D` args under PowerShell.)
- New modules in the reactor: `registration-launcher-common`, `registration-launcher`.
- **Everything is currently uncommitted on `demo-dev`.**
