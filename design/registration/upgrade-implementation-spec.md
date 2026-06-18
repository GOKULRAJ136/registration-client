# Registration Client Upgrade — Implementation Spec (working companion)

> **Status:** working / implementation notes.
> **Source of truth:** [`registration-upgrade.md`](./registration-upgrade.md) remains authoritative (per lead). This file captures implementation-level detail, decisions, and algorithms that the design doc leaves open. Where the two ever conflict, `registration-upgrade.md` wins.
> **Branch:** all work on `demo-dev`.

## Decisions

| # | Decision | Choice | Status |
|---|----------|--------|--------|
| 1 | Native-exe toolchain (`migration.exe` / `rollback.exe`) | TBD — must have no JVM dependency (Go prototype removed 2026-06-17) | **REOPENED** |
| 2 | Manifest signing & verification | **Reuse `keystore.p12` + `provider.pem`** (detached sig) | DECIDED (lead-approved 2026-06-16) |
| 3 | Shared code location | **Extract Java-11 `registration-launcher-common` module** | DECIDED (lead-approved 2026-06-16) |
| 4 | Platform scope | **Windows-only** | DECIDED (lead-approved 2026-06-16) |
| — | Resumable download design | HTTP `Range` + `.part` staging + atomic move | DECIDED (implemented) |
| — | Java-11 build in Java-21 reactor | per-module `maven-compiler-plugin` `<release>11</release>` | DECIDED |
| — | Test strategy | JUnit for launcher branching logic; native exes + real JRE swap → Windows acceptance checklist mapped to P1–P10 / N1–N14 | DECIDED |

---

## Signature verification — current vs. new (Decision #2 detail)

**Key takeaway:** Decision #2 reuses the *same key material* already in the build, but the thing being signed is *new*. Today the `MANIFEST.MF` file is **not** signed; the new design adds a **detached signature over the manifest file itself**.

### Current ("old manifest") approach — verified in code on `demo-dev`

The `MANIFEST.MF` is **NOT signed**. There is **no `MANIFEST.MF.sig`** anywhere in the codebase. Manifest trust is *indirect*:

1. **Build (`configure.sh`):**
   - `jarsigner -keystore keystore.p12 … CodeSigning` signs the two JARs (`registration-client.jar`, `registration-services.jar`).
   - `ManifestCreator` writes `MANIFEST.MF` as **plain text**, one entry per lib file with a per-file HMAC checksum (`Attributes.Name.CONTENT_TYPE` = `HMACUtils2` digest). The manifest is not signed.
2. **Runtime:**
   - `ClientIntegrityValidator.verifyClientIntegrity()` verifies the **signed JARs** against the embedded `provider.pem` (trusted X509 cert).
   - `SoftwareUpdateUtil.validateJarChecksum()` checks each lib file's bytes against the HMAC listed in `MANIFEST.MF`.

**Consequence:** a tampered `MANIFEST.MF` is only caught if it references jars that fail jar-signature verification. The manifest has no integrity of its own.

```
keystore.p12 ──(jarsigner)──> signs JARS (registration-client.jar, registration-services.jar)
provider.pem ──(runtime)────> verifies SIGNED JARS  (ClientIntegrityValidator)
MANIFEST.MF  ──> plain text, per-file HMAC only, UNSIGNED  (ManifestCreator / validateJarChecksum)
```

### New approach (design 1.3.0+)

Reuse `keystore.p12` + `provider.pem`, but apply them to a **new artifact**: a **detached signature of the manifest file**, for **both** manifests.

- **Build (`configure.sh`, T5 — net-new):**
  - Produce `./MANIFEST.MF.sig` (root manifest = version/orchestration).
  - Produce `lib/MANIFEST.MF.sig` (lib manifest = per-file hashes), bundled inside `lib.zip`.
  - Signing: detached `SHA256withRSA` over the manifest bytes using the existing `keystore.p12` private key.
- **Runtime (`_launcher.jar`, T2 — net-new):**
  - On every startup, verify `MANIFEST.MF.sig` using the public key embedded in `_launcher.jar` (from `provider.pem`) **before** trusting any hashes inside the manifest.
  - Drives security Cases A–D in the design doc (sig valid → trust hashes; sig invalid → abort, do **not** re-download — possible MITM).

```
keystore.p12 ──(configure.sh)──> NEW: detached sig of ./MANIFEST.MF      -> ./MANIFEST.MF.sig
                                 NEW: detached sig of lib/MANIFEST.MF    -> lib/MANIFEST.MF.sig (inside lib.zip)
provider.pem ──(embedded in _launcher.jar)──> NEW: verify MANIFEST.MF.sig before trusting manifest hashes
```

**Net:** "reuse keystore + provider.pem" = same key/cert infra (low friction, already present) + a **new detached-signature step over the manifest**. It is *not* a continuation of the old jar-signing flow.

> Cross-ref memory: `reference_manifest_signing_current_vs_new`.

---

## Open items / design ambiguities found during implementation

1. **lib.zip hash verification (step 5).** The design doc step 5 says *"verify lib.zip hash against entry in .TEMP/MANIFEST.MF"*, but the Dual-MANIFEST table states `lib/MANIFEST.MF` carries **per-file** hashes and **no lib.zip entry** (and a note says lib.zip is in no manifest). These contradict. **Resolution implemented:** verify the lib manifest's **signature**, keep the verified manifest in memory, then after unzip verify **each extracted file** against its per-file hash AND reject any extra file not listed (allowlist), and restore the verified manifest on disk so the archived copy can't replace it. `LibUpdater` + `ManifestVerifier.findMismatchedFiles`/`findUnexpectedFiles`. *Confirm with lead/doc owner.*
4. **Root-manifest hash coverage (T5).** The launcher now integrity-checks `jre21.zip` / `migration.exe` / `rollback.exe` / `_launcher.jar` against the signature-verified **root** `MANIFEST.MF` before unzipping/copying (`JreMigrationStager.verifyArtefactsAgainstRootManifest`). For this to actually gate, **the root `MANIFEST.MF` must carry per-file `Content-Type` hashes for those artefacts** — the build (`configure.sh`, T5) must write them. If an entry is absent the launcher logs a warning and cannot verify that artefact. *Decide + implement in T5.*
2. **lib.zip must be flat.** For `.TEMP/*` → `lib/` copy (run.bat) to land jars in `lib/`, `lib.zip` entries must be jars at the archive root (not under a `lib/` prefix). configure.sh today does `zip -r reg-client.zip lib` (prefixed) — **T5 must produce a flat lib.zip** (or LibUpdater/extract must strip the prefix). Recorded for T5.
3. ~~**Launcher config / upgrade-server URL resolution.**~~ **RESOLVED.** `LauncherConfig` reads `mosip-application.properties` (app root): `mosip.client.upgrade.server.url` fed into the `mosip.reg.client.url` template, then `{base}/{version}/...` (version from `./MANIFEST.MF`). The `SIGNATURE_MISSING` (Case C) and `UPDATE_LIB` (step 5) branches in `Initialization` are now wired to it.

---

## Native executables — shared concerns (T3 / T4)

> **Toolchain status (2026-06-17):** a Go implementation was prototyped then removed; **Decision #1 (toolchain) is being reconsidered.** The algorithm below is language-neutral. Whatever toolchain is chosen, the binary **must carry no JVM dependency** (it runs while the JRE is being swapped) and target **Windows** (Decision #4).

The executable must carry **no JVM dependency** and cannot be broken by the JRE swap it performs. Windows-only (Decision #4).

- **No JRE dependency.** They run as standalone processes after `_launcher.jar` exits; they must not invoke `java`/`javaw` or rely on `jre/` (it is being replaced).
- **Operator dialogs.** Use the Win32 `MessageBox` (`user32.dll` via `syscall`); if that fails (e.g. service/headless), fall back to stdout + log and continue. Never block indefinitely.
- **Logging.** Append to a dedicated log (e.g. `logs/migration.log`) — these run when the Java logging stack is unavailable.
- **Working directory = application root.** All relative paths (`jre/`, `jre11/`, `jre21_temp/`, `lib/`, `.TEMP/`, `.artifacts/`, `run.bat`, `run.bat_jre11`) resolve against it.
- **Exit codes.** `0` = success (operator-facing dialog shown); non-zero = failure (after attempting rollback where applicable).
- **Idempotent + resumable.** Every step is guarded by a state check so a re-run after a crash/force-kill converges to a valid state (Scenarios 1, 3, 5).

### Filesystem state model

| Path | Meaning |
|------|---------|
| `jre/` | the live JRE that `run.bat` launches (`jre\bin\javaw`) |
| `jre11/` | backup of the original Java 11 JRE (created by `migration.exe`) |
| `jre21_temp/` | Java 21 JRE unzipped from `.artifacts/jre21.zip`, staged for promotion |
| `lib/` | live classpath jars |
| `.TEMP/` | new jars from `lib.zip`, applied to `lib/` by `run.bat` on next start |
| `.artifacts/` | downloaded migration artefacts (`jre21.zip`, `_launcher.jar`, `migration.exe`, `rollback.exe`, `run.bat`, `lib.zip`) |
| `run.bat` / `run.bat_jre11` | live launcher / backup of the Java 11 launcher |

### Preconditions set by `_launcher.jar` step 3 before launching `migration.exe`

(See design step 3.) Artefacts present in `.artifacts/`; `lib.zip` unzipped → `.TEMP/`; `jre21_temp/` unzipped from `.artifacts/jre21.zip`; `migration.exe`/`rollback.exe` copied to app root; `run.bat` backed up → `run.bat_jre11`. Then `_launcher.jar` starts `migration.exe` and exits the JVM.

---

## `migration.exe` algorithm (T3)

Performs the JRE swap as a separate, idempotent process. **Convergence invariant:** after a successful run, `jre/` is Java 21, `jre11/` holds the old JRE, `lib/` contains only `_launcher.jar`, and `run.bat` is the new launcher.

```
migration():
  log("migration.exe started")

  # 1. backup the original Java 11 JRE (idempotent)
  if detectJreMajor("jre") == 11 and not exists("jre11/"):
        rename("jre/", "jre11/")            # atomic on same volume

  # 2. promote the Java 21 JRE
  if not exists("jre21_temp/"):
        if not exists(".artifacts/jre21.zip"):
              fail -> rollback("jre21.zip missing")
        unzip(".artifacts/jre21.zip", "jre21_temp/")   # may throw on disk-full -> rollback
  if exists("jre/"):
        deleteRecursive("jre/")
  rename("jre21_temp/", "jre/")              # promotion

  # verify the promotion produced a usable JRE
  if detectJreMajor("jre") < 21:
        fail -> rollback("post-promotion JRE not 21+")

  # 3. refresh lib/ to just the new launcher (run.bat applies .TEMP/* on next start)
  deleteAllUnder("lib/")
  copy(".artifacts/_launcher.jar", "lib/_launcher.jar")

  # 4. install the Java 21 launcher script
  copy(".artifacts/run.bat", "run.bat")

  # 5. done — operator restarts; run.bat copies .TEMP/* -> lib/, _launcher.jar sees JRE 21 + versions match
  dialog("JRE migration complete. Please start the application using run.bat.")
  exit(0)

  on any unexpected error E:
        rollback(E)                          # invoke rollback.exe, then exit(1)
```

**Failure detection → `rollback.exe`** (design Scenario 1, 5): any exception during steps 1–4, **or** the guard `jre11/ exists AND (jre/ missing OR detectJreMajor(jre) != 21)`, triggers `exec("rollback.exe")` and a non-zero exit. Disk-full during unzip (N9) is one such error.

**Idempotency / resume (Scenario 3 — force-kill):** re-running is safe — step 1 skips if `jre11/` exists; step 2 skips re-unzip if `jre21_temp/` already present, else recreates it (deleting any partial); steps 3–4 are overwrite-copies. `_launcher.jar` re-invokes `migration.exe` on next start if it still detects JRE 11 with `jre21_temp/` present.

---

## `rollback.exe` algorithm (T4)

Restores the pre-migration state. **Idempotent and safe to run repeatedly.** Triggered by: `migration.exe` failure; incomplete `jre21_temp/` unzip; disk exhaustion; `_launcher.jar` detecting an inconsistent/unknown JRE on startup; operator force-kill leaving an unrecognisable JRE; or the operator running it manually (design step 4a trigger table).

```
rollback():
  log("rollback.exe started")

  # 1. restore the original Java 11 JRE
  if exists("jre11/"):
        if exists("jre/"):
              deleteRecursive("jre/")
        rename("jre11/", "jre/")
  # else: nothing backed up yet -> skip (N12)

  # 2. discard the staged Java 21 JRE
  if exists("jre21_temp/"):
        deleteRecursive("jre21_temp/")

  # 3. restore the Java 11 launcher script
  if exists("run.bat_jre11"):
        if exists("run.bat"):
              delete("run.bat")
        rename("run.bat_jre11", "run.bat")

  # 4. clear the staged lib update
  deleteAllUnder(".TEMP/")

  dialog("Rollback complete. Application restored to previous state. Please start using run.bat.")
  exit(0)
```

**Must NOT do** (design step 4a): does **not** touch `./MANIFEST.MF` — the version mismatch is left intact so `softwareUpdateHandler` re-downloads artefacts on the next version check and the operator can retry migration. Does **not** re-download anything.

**Edge cases covered:** N12 (`jre11/` absent → skip JRE restore, still clean `.TEMP/`/restore `run.bat`); repeated runs converge (each step guarded by existence checks).

---

## Acceptance mapping (native exes)

| Scenario | Covered by |
|----------|-----------|
| N5 — migration fails mid (disk full on rename) | `migration.exe` failure guard → `rollback.exe` |
| N6 — force-kill, JRE still 11 on restart | `_launcher.jar` re-invokes `migration.exe` (idempotent, `jre21_temp/` reused) |
| N7 — force-kill, JRE unknown on restart | `_launcher.jar` → `rollback.exe` |
| N8 — disk full unzipping `jre21.zip` in `_launcher.jar` | error dialog + exit; partial `jre21_temp/` deleted + retried next start |
| N9 — disk full unzipping inside `migration.exe` | `migration.exe` → `rollback.exe` |
| N12 — `rollback.exe` with no `jre11/` backup | rollback skips JRE restore, still cleans `.TEMP/`/`run.bat` |

> These run on Windows only and are validated via the Windows acceptance checklist (not JVM unit tests). `detectJreMajor(dir)` runs `dir\bin\java -version` (or reads `release` file) — implementation detail for the Go build (T3).
