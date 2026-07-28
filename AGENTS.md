# qits-ci — working notes

Read `README.md` first: it defines the boundary (what arrives over HTTP, what ci fetches for
itself) and the config surface. This file is the working conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials. `mvn verify` is the gate. Anything that would break that is
not a tradeoff to weigh, it is the thing this repo exists to avoid.

That is why: the poms duplicate versions instead of inheriting them, the suites stand up their own
bare git repos instead of using fixture submodules, the git host is a `file://` directory laid out
as `<base>/git/<repoId>`, and the one seam that needs real docker is faked (`FakeCiStepRunner`)
rather than skipped.

**`service/` compiles to a GraalVM native image**, the same rule qits-gateway and
qits-workspace-daemon carry. `.sdkmanrc` names `25.0.2-graalce`, so `sdk env` gives you a
`native-image` and `./mvnw verify -Dnative` produces `service/target/qits-ci` in about two minutes
with no container involved. Do not read that as a qualification of the clone-alone rule; it is a
second rule of the same kind, and three things follow:

- **A missing GraalVM does not fail the build.** Quarkus logs `Cannot find the native-image ...
  Attempting to fall back to container build` and shells out to docker for a 1.8 GB Mandrel image.
  Green either way, so the fallback is easy to be in without noticing. Grep a native build's log for
  that line before believing it proved anything. (This context shells out to `docker` at *runtime*
  by design, which makes the word useless as a signal in a log — look for the line, not the word.)
- **Every dependency is a decision about what the builder has to be told.** Reflection, dynamic
  proxies, `ServiceLoader`, resources loaded by computed name and JNI/JNA all need registering, and
  when they are missing the failure lands at *runtime, in the binary*, while the JVM suite stays
  green. Prefer what is already in the image — `ProcessBuilder` over a process library (which is why
  `CiDockerRunner` and `GitConfigFetcher` shell out rather than link a docker or git client), and
  `java.lang.foreign` over JNA. If a native build needs configuration to pass, that configuration is
  part of the change.
- **So is every config default the app boots with.** `quarkus.datasource.ci.jdbc.url` carried
  `AUTO_SERVER=TRUE` out of the monorepo; it asks H2 to start its own TCP server, whose classes are
  not in the image, and the binary died at boot on a default no JVM test ever used. It was dropped
  rather than registered — see the note in `ci/src/main/resources/META-INF/microprofile-config.properties`.
  `CiPackagedSurfaceIT` is the guard, and it relocates `user.home` rather than restating the
  settings precisely so that the shipped values stay the ones under test.

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

## Addressing

`README.md` has the shape; two things bite when you change a path here.

**`quarkus.rest.path=/ci/api` lives in `service/src/main/resources/application.properties` and the
suite inherits it.** So a resource's `@Path` is relative to `/ci/api` and must never repeat `ci`.
Tests address the absolute path, which is what makes them catch a prefix regression.

**`CiTokenFilter` matches on `UriInfo.getPath()`, which is relative to `quarkus.rest.path`.** It
matches the literal `events`. Move or rename `CiEventController`'s `@Path` and the guard stops
matching — and it fails *open*, because a request the filter does not recognise is simply not
checked. `CiTokenGuardTest` is what stands between that and shipping: it POSTs the intake's real
address with no token and demands a 401, so a filter that quietly stopped guarding shows up as a
202. Change the two together and keep that test on the absolute path.

## Adding a dependency on another context

Don't. This context has no compile-time dependency on any other qits module and should not grow
one. Things arrive as an HTTP payload on the intake, or as a URL in config, or not at all. There is
no `RepositoryLookup`-style port here and there should be no need for one: a run knows a repo id, a
branch name and a sha, and everything else it wants it fetches from the git host itself.

Never add a JPA relation to another context's entity. `ci_run.repo_id` is a plain `String` column
in ci's **own** physical database; a foreign key cannot span it.

## Untrusted input

Two things reaching this code are attacker-controlled and must stay that way in your head:

- **The intake payload.** `/ci/api/events/` sits on the token-free allowlist and the token defaults
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

- App-level config lives in `service/src/main/resources/application.properties` — this module is the
  deployable, and Quarkus merges that file into the test config rather than letting
  `src/test/resources/application.properties` shadow it. **Never re-declare an app-level setting in
  test resources**: a suite green because the *test* copy is right proves nothing about what ships,
  and the two silently drift. `src/test/resources/application.properties` carries only genuine
  test-only overrides (in-memory H2, `target/` data dir, the `file://` git-host stand-in).
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
  `CiPackagedSurfaceIT` is deliberately outside that race: failsafe passes it
  `quarkus.http.test-port=0`, so the packaged app it launches takes a free port instead of queueing
  behind whatever surefire has not finished releasing.
- `CiPackagedSurfaceIT` is the only test that runs the **packaged artifact** — the fast-jar under
  `-DskipITs=false`, the binary under `-Dnative`. It is not a second boundary test and behaviour
  does not belong in it: it asserts the handful of things a `@QuarkusTest` structurally cannot see,
  because they only exist once the app is built (the routes' build-time prefixes, the shipped
  datasource URL, Flyway's migration surviving as a resource, SnakeYAML and Panache on a real run).
  Its pipeline declares no steps, so it needs no container; step execution stays in
  `CiDockerRunnerIT`.
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
  It is tagged `extended`, and the `native` profile excludes that tag (`qits.it.excluded-groups` in
  the root pom) while flipping `skipITs`: a native build has to run the ITs to be worth anything,
  and this one would fail it for reasons that are about the host's networking rather than the
  binary. `-DskipITs=false` still runs it, unchanged.
