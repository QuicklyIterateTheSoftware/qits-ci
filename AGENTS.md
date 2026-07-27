# qits-ci — working notes

Read `README.md` first: it defines the boundary (what arrives over HTTP, what ci fetches for
itself) and the config surface. This file is the working conventions on top of it.

## The one rule that shapes everything

This repo must build and test green from a **clone of itself alone** — no monorepo, no docker, no
prior `mvn install` elsewhere, no credentials. `mvn verify` is the gate. Anything that would break
that is not a tradeoff to weigh, it is the thing this repo exists to avoid.

That is why: the poms duplicate versions instead of inheriting them, the suites stand up their own
bare git repos instead of using fixture submodules, the git host is a `file://` directory laid out
as `<base>/git/<repoId>`, and the one seam that needs real docker is faked (`FakeCiStepRunner`)
rather than skipped.

## Package and module conventions

`eu.wohlben.qits.ci.*`, split across two maven modules with disjoint sub-packages so there is no
split package:

- `ci/` — `entity`, `persistence`, `dto`, `mapper`, `control`, `error`. Framework-free in the sense
  that matters: no JAX-RS. Entities are Panache; mappers are MapStruct
  `@Mapper(componentModel = "jakarta")`.
- `service/` — `api` only: the JAX-RS routes, the `ContainerRequestFilter` and the
  `ExceptionMapper`.

The **directories** are `ci/` and `service/`; the artifactIds are `qits-ci-domain` and
`qits-ci-service`. The mismatch is deliberate — the extracted git history is anchored to the
directory names, and generic coordinates like `eu.wohlben:ci` would collide in the shared `~/.m2`
that every workspace container mounts.

## Adding a dependency on another context

Don't. This context has no compile-time dependency on any other qits module and should not grow
one. Things arrive as an HTTP payload on the intake, or as a URL in config, or not at all. There is
no `RepositoryLookup`-style port here and there should be no need for one: a run knows a repo id, a
branch name and a sha, and everything else it wants it fetches from the git host itself.

Never add a JPA relation to another context's entity. `ci_run.repo_id` is a plain `String` column
in ci's **own** physical database; a foreign key cannot span it.

## Untrusted input

Two things reaching this code are attacker-controlled and must stay that way in your head:

- **The intake payload.** `/api/ci/events/` sits on the token-free allowlist and the token defaults
  to blank. `CiIdentifiers.require{RepoId,Branch,Sha}` validates all three *before* they reach a
  filesystem path or an argv. Never widen those, never bypass them, never interpolate an identifier
  into a shell string.
- **The step script.** It is code from a repository. It rides into the container as a bash
  positional argument, never spliced into the prelude, and the container gets `--cap-drop=ALL`,
  `no-new-privileges` and resource caps. Anything that would hand a step more privilege — a docker
  socket, a host mount, a shared network with services — is a security change, not a convenience.

Step output is bounded by a rolling tail while it is read, so a chatty step cannot OOM the JVM.
Keep it that way; do not buffer a step's output whole.

## Schema changes

`ci/src/main/resources/db/ci/migration/`, hand-written, its own lineage on its own datasource. It
was never part of the monorepo's shared `db/migration` lineage, so it came across from the
extraction unsquashed and unchanged — keep appending to it.

## Authentication

Authentication happens at `qits-gateway`. This service resolves a principal from a trusted header
(`X-Qits-User`, read by `ci/security/ForwardAuthMechanism`) and authenticates nothing.

**`identity.isAnonymous()` is not a security state** — it means "no name for the audit row". A check
of the form `if (identity.isAnonymous()) deny` would look like a security control and be worth
nothing, because reaching this service at all already implies you are inside the trusted network.

There is no auth variant to select and no authorization policy here, and roles are deliberately not
resolved — the single role check the system has (`qits.auth.required-role`) is the gateway's. See
`migration-auth-plan.md`.

## Tests

- `service/src/test/resources/application.properties` is **no longer the only copy** of
  `quarkus.rest.path` — `src/main/resources/application.properties` carries it for the packaged
  process. Change one and you must change both; a suite green because the *test* copy is right
  proves nothing about what ships.
- `OpenApiSchemaExportTest` writes `docs/openapi.yml`. Regenerate and commit when the surface
  changes: `./mvnw -pl service test -Dtest=OpenApiSchemaExportTest`.
  **`paths: {}` is the correct output here and not a broken generator** — all three ci operations
  carry `@Operation(hidden = true)`, because they are machine surfaces rather than part of the JSON
  API the Angular client consumes, and the monorepo's own document omits them for the same reason.
  The file is committed anyway so that *unhiding* one shows up as a diff.
  Note the test runs as a `@QuarkusTest` and indexes the test classpath, so a `@Path` resource under
  `src/test` would land in the document — that is why `IdentityEchoResource` is hidden too.
- A `Failed to start quarkus` / `Port already bound: 8081` failure is the known flake
  (`migration-plan.md` §9 item 14) — `@QuarkusTest` restarts racing for the test port. Re-run first.
- The ci module's suite is plain JUnit plus `@QuarkusTest`, and fakes the runner
  (`ci/src/test/.../FakeCiStepRunner` scripts a `StepResult` per step index).
- The service module's `FakeCiStepRunner` is a **different, honest** fake: it performs the real step
  semantics (clone at the pushed sha, `bash -c <script>`) as host processes. The two fakes are
  duplicated on purpose — the modules do not share a test classpath.
- `CiPipelineBoundaryTest` starts at the intake POST, not at a `git push`, because the git host is
  in qits-artifacts. Assertions about what the git host's hook does or does not send belong there.
- `CiDockerRunnerIT` needs real docker, a built `qits/workspace` image, **and** a step container
  that can reach `host.docker.internal` on the `qits.ci.network` it joins. `skipITs=true` is the
  default so `mvn verify` is runnable anywhere; run it with `-DskipITs=false`. Its JUnit assumption
  only covers the first two — on a host where the container cannot route back to the JVM (plain
  WSL2, no compose stack up) it fails rather than skips. That is a property of the IT, carried over
  from the monorepo unchanged; do not "fix" it by weakening the assertions.
