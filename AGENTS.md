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

`eu.wohlben.qits.ci.*`, split across maven modules with disjoint sub-packages so there is no split
package:

- `ci/` — `entity`, `persistence`, `dto`, `mapper`, `control`, `error`. Framework-free in the sense
  that matters: no JAX-RS, no websockets. Entities are Panache; mappers are MapStruct
  `@Mapper(componentModel = "jakarta")`.
- `service/` — `api` (the JAX-RS routes, the `ContainerRequestFilter` and the `ExceptionMapper`),
  `security`, and `daemonhost` (the ci-daemon control socket, the launch registry and the container
  launcher). It read "`api` only" until the daemon control plane landed; the transport lives beside
  the API because it needs a web stack, which is the same line that put `api` here rather than in
  `ci/`, and it is where qits-workspaces keeps its own `daemonhost` for the same reason. `ci/` keeps
  the `CiStepRunner` seam and the orchestrator and gains no web dependency.
- `ci-daemon-protocol/` — the vendored wire contract (below). Its package is
  `eu.wohlben.qits.cidaemon.protocol`, deliberately not under `eu.wohlben.qits.ci`: it is a copy of
  another repo's module and its package must stay byte-identical with the original.

The **directories** are `ci/`, `service/` and `ci-daemon-protocol/`; the artifactIds are
`qits-ci-domain`, `qits-ci-service` and `qits-ci-daemon-protocol`. The first two mismatch
deliberately — the extracted git history is anchored to the directory names, and generic coordinates
like `eu.wohlben:ci` would collide in the shared `~/.m2` that every workspace container mounts.

## The vendored protocol module

`ci-daemon-protocol/` is a copy of
[qits-ci-daemon](https://github.com/QuicklyIterateTheSoftware/qits-ci-daemon)'s module of the same
name: same java package, different artifactId, so the two jars can never collide while the day the
artifact is published somewhere stays a one-line change. It is copied rather than depended on
because that module is published to no registry and the clone-alone rule is not negotiable.

**Never edit this copy.** The daemon repo owns the contract; a change lands there, bumps
`CiDaemonProtocol.CAPABILITY_VERSION`, and is re-copied whole:

    diff -r ../../daemons/qits-ci-daemon/ci-daemon-protocol/src ci-daemon-protocol/src

must be silent. `CiDaemonCodecTest` travels with the copy and runs on both sides, so a drift fails a
build rather than a socket. A "small fix" applied here instead is how the workspace pair drifted once
already (`migration-plan.md` §9 item 19), and this is knowingly the third such mirrored pair.

## The ci-daemon control plane

`service/…/daemonhost/` is the host half of the arrangement qits-workspaces has with its own daemon:
a step container runs `qits-ci-daemon`, which **dials out** to qits-ci and receives its step as the
reply to its own `Initialized`. qits-ci never dials in, and — the invariant the whole feature rests
on — **no code path here runs repo-controlled code as a host process or through `docker exec`**. The
docker vocabulary is container lifecycle only. `CiDaemonLauncher.BOOTSTRAP` is a `static final
String` with zero interpolation; a step script never appears in an argv.

Three things bite:

- **`@WebSocket(path = "/ci/daemon")` is a literal that does not follow `quarkus.rest.path`**, so it
  carries the `/ci` segment itself — and it is outside `CiTokenFilter`'s reach by construction, since
  that filter matches `UriInfo.getPath()` relative to `quarkus.rest.path`. Correct rather than an
  oversight: the callers are containers holding no intake token, and the authentication is the
  per-container secret.
- **The path is a cross-repo contract.** `qits.ci.container-daemon-url` (default
  `ws://qits-ci:8080/ci/daemon`) is injected as `$QITS_CI_DAEMON_URL` and dialled verbatim. Move one,
  move both. It is not a gateway route and must not become one: one process per container with a
  lifetime of one step has no stable address worth configuring.
- **No untimed wait may enter this package.** The single-threaded run worker parks here instead of on
  a process, so a future that never completes wedges *all* of CI. Every await carries its
  transition's timeout; `CiDaemonRegistryTimeoutTest` holds that behaviourally *and* by grepping the
  package's sources for `.get()`, `.join()` and `sendTextAndAwait`. If you add an await, give it a
  deadline or that test tells you so.

Phase B landed this behind `CiDockerRunner`, which still executes production steps: the registry,
socket and launcher are reachable only from `CiDaemonHandshakeIT` until the seam swaps.

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
- **Everything arriving over the ci-daemon control socket.** A container turns hostile the moment
  step code runs in it, so its frames are data about a run: recorded, never trusted. The `daemonId`
  in a `Hello` is a claim the host checks against the connection it already authenticated rather than
  an identity it accepts; timestamps are host-stamped rather than daemon-reported, because a clock is
  the cheapest thing to forge; and the per-container secret authorizes exactly "deliver data about
  this run" and nothing else, ever.

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
resolved — the single role check the system has (`qits.auth.required-role`) is the gateway's. The
gateway authenticates every human request (OIDC, with the variant fixed at **build** time via
`-Dqits.variant`, so no runtime setting can reopen a gateway built as `oauth`) and asserts the
result as headers. `X-Qits-*` is its reserved namespace and is stripped from every inbound request
unconditionally, which is the entire reason a header can be trusted as an identity here.

`qits.ci.token` is not part of any of this. It guards one machine-to-machine path and knows nothing
about users, so edge auth neither replaces it nor excuses it — `CiTokenGuardTest` stays exactly as
load-bearing as it was.

`ForwardAuthTest` sets a real `X-Qits-User` rather than reaching for `@TestSecurity`, and that is
deliberate: the header **is** the contract under test. `@TestSecurity` installs an identity without
going through the mechanism, so it would pass just as well against a service that shipped no
mechanism at all — which is what every service here was before the header landed.

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
  datasource URL, Flyway's migration surviving as a resource, SnakeYAML and Panache on a real run,
  and that `/ci/daemon` is on the artifact's router). Its pipeline declares no steps, so it needs no
  container; step execution stays in `CiDockerRunnerIT`.
  The `/ci/daemon` assertion is there because websockets-next registers that endpoint at
  *augmentation*: "the extension is native-image supported" is a claim the binary has to prove here,
  and a native build that silently dropped the route would otherwise surface as every run stuck at
  "never registered" with nothing in any log to say why. It dials with credentials no registry can
  know and asserts the **upgrade succeeds and the server then closes 1008** — a missing route fails
  the upgrade with a 404 instead.
- `CiDaemonSocketTest` drives the real socket with a real WebSocket from `FakeCiDaemon`, an in-JVM
  dialler framing the real protocol exactly as the binary does. The host cannot tell it from a
  container, which is the point: admission, framing, dispatch and the blocking bridge are all
  provable with no docker and no published binary, and only the round trip through a real image is
  left to the gate.
- `CiDaemonHandshakeIT` is the **phase-B gate**: a real container from `buildpack-deps:scm` (verified
  to carry git, bash, wget *and* curl — the whole image contract), a real download of the daemon
  binary from a file-served stand-in, a real dial back, a real step. Tagged `extended`, run with
  `-DskipITs=false`, excluded from the `native` profile exactly as `CiDockerRunnerIT` is and for the
  same reason. It carries **the same host-networking assumption and the same caveat**: the assumptions
  cover docker, the image and the binary, but not the container's route back to the JVM through
  `host.docker.internal`, so on a host without one it fails rather than skips — do not "fix" that by
  weakening the assertions.
  The daemon binary is `-Dqits.ci.daemon-binary=<path>`, not a fixture: `$QITS_CI_DAEMON_BINARY_URL`
  can point anywhere, which is precisely why it is env, so the gate never waits on a publish to
  qits-artifacts. Without the property the two round-trip cases skip and the never-registers case —
  which needs only docker — still runs and asserts its `docker logs` capture.

  **Three environmental hazards cost a day between them, and all three are now handled in the tree.
  Each fails in a way that blames the wrong thing, so do not undo any of them:**

  1. **The JVM must bind IPv4.** Left alone it binds a dual-stack IPv6 socket for `0.0.0.0` — `ss
     -ltn -f inet` shows nothing, `-f inet6` shows `*:<port>` — and docker's host gateway forwards
     only IPv4, so every listener the test stands up is invisible to the container. `service/pom.xml`
     gives failsafe `-Djava.net.preferIPv4Stack=true`; it has to be an `argLine` because the JVM
     reads it when networking initialises, before any test runs. Same line qits-workspaces carries,
     for the same reason.
  2. **Host-gateway forwarding lags a freshly-bound listener.** A container started the instant
     `listen()` returns gets `Connection refused`; two seconds later the same port serves 45MB fine.
     The bootstrap fetches *once* and exits, so the container is dead within a second and the host
     then waits out its whole 120s register deadline — surfacing as `wget could not fetch`, which
     reads like a broken url. `awaitReachableFromAContainer` gates the fixture on a real TCP connect
     from a real container first. Deliberately **not** fixed by retrying in `BOOTSTRAP`: the race is
     the fixture's (production ports belong to long-lived services), and papering over a harness
     artefact by changing shipped behaviour is the wrong trade.
  3. **The git fixture must serve *smart* HTTP.** The daemon clones `--depth 50`, and shallow is a
     capability only the smart transport advertises — a static-file handler gets exactly as far as
     `fatal: dumb http transport does not support shallow capabilities`. The fixture shells `git
     http-backend` as CGI, which is what qits-artifacts does behind `/git/<repoId>`. Do not "fix" a
     recurrence by dropping `--depth`: depth 50 is deliberate (a recent-but-not-tip sha must still be
     in the clone) and the daemon is behaving correctly for production.
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
