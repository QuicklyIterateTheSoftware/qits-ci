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

**The one clause that has been bought back is "alone":** the Angular client is a submodule, so the
gate is now `git submodule update --init && ./mvnw verify`. Everything else about the rule holds —
still no monorepo, still no credentials, still no prior install — and the cost is one public clone,
paid once. It is called out here rather than folded quietly into the sentence above because the
failure it introduces looks like a broken build rather than a missing checkout; see "The Angular
client".

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
  `CiProcess` and `GitConfigFetcher` shell out rather than link a docker or git client), and
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
  `security`, and `daemonhost` (the ci-daemon control socket, the launch registry, the container
  launcher, the live relay and `CiDaemonStepRunner` — the sole implementation of the step seam). It
  read "`api` only" until the daemon control plane landed; the transport lives beside the API because
  it needs a web stack, which is the same line that put `api` here rather than in `ci/`, and it is
  where qits-workspaces keeps its own `daemonhost` for the same reason. `ci/` keeps the
  `CiStepRunner` seam and the orchestrator and gains no web dependency — the step runner is in
  `service/` because it *is* the transport. Two more packages of the same kind: `notify`, the cd
  announcement, and `bus`, both ends of the event bus (below). Every one of them is an *adapter* for
  a seam that lives in `ci/control`; that is what the package split says.
- `ci-daemon-protocol/` — the vendored wire contract (below). Its package is
  `eu.wohlben.qits.cidaemon.protocol`, deliberately not under `eu.wohlben.qits.ci`: it is a copy of
  another repo's module and its package must stay byte-identical with the original.
- `eventsourcing/` — the event bus client (below). Its package is `eu.wohlben.qits.eventsourcing`,
  and it may not import `eu.wohlben.qits.ci.*` at all.
- `ci-events/` — the event classes qits-ci emits, `eu.wohlben.qits.ci.events`. Under this repo's own
  namespace because it *is* this repo's vocabulary; depends on `eventsourcing` and nothing else.

The **directories** are `ci/`, `service/`, `ci-daemon-protocol/`, `eventsourcing/` and `ci-events/`;
the artifactIds are `qits-ci-domain`, `qits-ci-service`, `qits-ci-daemon-protocol`,
`qits-eventsourcing` and `qits-ci-events`. The first two mismatch deliberately — the extracted git
history is anchored to the directory names, and generic coordinates like `eu.wohlben:ci` would
collide in the shared `~/.m2` that every workspace container mounts. `eventsourcing/` mismatches for
the same collision reason in the other direction: the directory keeps the generic name because that
is the name it will carry into its own repository.

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
  a process, so anything that never returns wedges *all* of CI. That covers three kinds of wait, not
  one: the lifecycle futures (`CiDaemonRegistry.await`), writing a frame (`send`), and closing a
  socket (`closeBounded`). `CiDaemonRegistryTimeoutTest` holds it behaviourally *and* by grepping
  this package's sources.

  **The `…AndAwait` family is banned by shape, and that generalisation was bought.** The grep first
  listed `sendTextAndAwait` and `sendBinaryAndAwait` by name, on the correct reasoning that each is
  `sendText(m).await().indefinitely()` — and then sat green over two live `closeAndAwait` calls,
  which are the identical shape under a name nobody had enumerated. One of them was on the reap
  path, so the step-timeout backstop closed a socket whose peer is *by definition* not answering,
  on the run worker, with `docker rm -f` as the next statement: a hang there would have wedged CI
  and orphaned the container from a single cause. The untimed part of these lives inside the
  framework's default method, so it never appears in this package's own source and only the *call*
  is visible to a grep. Write the bounded form —
  `close(…).await().atMost(CLOSE_TIMEOUT)`, `sendText(m).await().atMost(SEND_TIMEOUT)` — and note
  that a close gets a much shorter deadline than a send, because a send is delivering something the
  run needs while a close is being polite to a peer that is about to be `rm -f`'d anyway.
  The pattern's own coverage is asserted against known strings in the same test: a guard that can be
  silently incomplete is worth exactly what its coverage is, and that coverage used to be unasserted.

This is the execution path, not a plan for one. `CiDaemonStepRunner` is the only implementation of
`CiStepRunner`; the approach it replaced — one `docker run` of a composite `bash -c` with a
clone/checkout prelude and a `PRELUDE_FAILED_MARKER` sentinel — was **eradicated**, not retired. It
does not exist in any form, there is no config toggle selecting it, and no fake performs its
semantics. If a `bash -c` of repository content ever reappears here, host-side or in a docker argv,
that is the regression, not a refactor.

Two more things live in this package and belong to it rather than to `ci/`:

- **`CiStepRelay`** is both halves of one bound. It is the live surface (`GET /ci/api/runs/{runId}`'s
  `live` object, polled — there is no SSE and no WebSocket) *and* the accumulator the persisted tail
  is read back out of at the step's end. One buffer, one budget: the bound is a security property
  and two implementations of it drift into one that is not applied.
- **Cancellation** is a flag plus a `Cancel` frame. A cancelled step still *finishes* — the daemon
  answers with a terminal frame — so the worker's await completes normally and cancelledness is read
  from `CiRunService`'s own flag, never inferred from how the call came back. Before a step has
  started there is nothing to cancel, so the launch is torn down instead, which completes the same
  await at once.

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

## The Angular client

`service/src/main/webui` is the [qits-spa-ci](https://github.com/QuicklyIterateTheSoftware/qits-spa-ci)
submodule, built and served by Quinoa. The path is Quinoa's default `web-ui-dir`, so it is a
convention rather than a setting, and the four config keys that are settings live in
`application.properties` under "the Angular client" with their reasoning beside them.

**The `/ci` segment is spelled three times and they move together**: `quarkus.quinoa.ui-root-path`
here, `quarkus.rest.path` beside it, and the client's own Angular `baseHref` in its `angular.json`.
The third one is not redundant — the browser resolves the client's asset urls against the document,
so a baseHref that disagrees with where the app is mounted yields a page that loads and then fetches
its own javascript from the wrong place.

**What SPA routing must not swallow is listed here, not derived — and `/ci/daemon` is why.** Quinoa's
fallback is a catch-all under `/ci` and the skip list it *derives* holds exactly two things,
`quarkus.rest.path` and `quarkus.http.non-application-root-path`. The daemon control socket is
outside it: `@WebSocket(path = "/ci/daemon")` is a literal that follows neither key.

This file used to argue that made no difference, because websockets-next registers its route at the
default order while Quinoa's SPA route is near-last, so an upgrade never reaches the SPA handler.
That is true of the **upgrade** and of nothing else. **Measured on the packaged fast-jar before
`quarkus.quinoa.ignored-path-prefixes` was set**, a plain `GET /ci/daemon` — no `Upgrade` header —
and `GET /ci/daemon/nope` each answered **200 `text/html`** with the SPA's `index.html`; the socket
route claims only the handshake and the fallback took the rest. `/ci/daemon` is a cross-repo machine
contract (`qits.ci.container-daemon-url`, dialled verbatim by every step container's daemon), and a
machine client handed a web page parses it as data. The correct answer to a mistyped machine path is
a 404, which is what it is now.

So the key is set: `quarkus.quinoa.ignored-path-prefixes=/api,/q,/daemon`. Setting it **replaces**
the derivation instead of extending it, which is why `/api` and `/q` are spelled out again by hand —
drop either and the API answers mistyped paths with `index.html`. The values are **relative** to
`ui-root-path` (`/api`, never `/ci/api`); an absolute value matches nothing and fails exactly like an
unset key, which is the failure that hides. The list moves when any of its three sources moves —
`quarkus.rest.path`, `quarkus.http.non-application-root-path`, or `CiDaemonSocket`'s `@WebSocket`
literal — so add a literal route and add its prefix in the same commit.

Ignoring a prefix stops the SPA **reroute**; it does not unregister the real route. The upgrade on
`/ci/daemon` still works, and `CiPackagedSurfaceIT` asserts it on the packaged artifact, which is
what keeps that from being a belief. The platform rule this follows in the general case: leave the
key unset when `quarkus.rest.path` and `quarkus.http.non-application-root-path` name the service's
whole machine surface, set it the moment a literal exists outside them.

**quarkus-undertow must never join this module's dependencies.** Its presence breaks Quinoa's
production static serving — the reason qits-artifacts mounts its git host on plain Vert.x routes
rather than as a servlet (that repo's README). Nothing pulls it in today; `./mvnw -pl service -am
dependency:tree -Dincludes=io.quarkus:quarkus-undertow` is empty and quarkus-vertx-http is the only
web stack present. Check that before adding any extension that sounds like a web framework.

**Three places assume the submodule is checked out, and each fails differently:**

- **A build.** An uninitialised gitlink is an *empty directory*, and that is the one case Quinoa
  treats as a misconfiguration rather than as "no client": `./mvnw verify` stops at `No package.json
  found in Web UI directory: 'src/main/webui'`. Loud, and it names the cause — but it is why the
  clone-alone rule at the top of this file now reads "clone **and** `git submodule update --init`".
- **The image build.** `docker/Dockerfile` does `COPY . .`, so the build context carries whatever the
  working tree has. The Mandrel builder stage has no node either, which is why that `mvnw` line
  passes `-Dquarkus.quinoa.package-manager-install=true` and a pinned `node-version` — on the command
  line rather than in `application.properties`, so a developer machine keeps using its own node.
- **This repo's own CI.** The step container's clone is `--depth 50` and does not recurse, so
  `.config/qits/ci-post-receive.yml` initialises the submodule itself before `docker build`. Without
  that line the publish step fails on the empty directory, and the repo's CI is red for a reason that
  has nothing to do with the push.

Note Quinoa is **disabled by default in test mode**, so no `@QuarkusTest` builds the client and the
suite's runtime is unchanged. What the SPA is actually served as is proven by `package` plus the
packaged artifact, not by surefire.

## The eventsourcing module

`eventsourcing/` is a **library that has not moved out yet**. It is the platform's event bus client
— `QitsEvent`, `QitsEventBus.publish`, `QitsEventListener` — and it lives here because qits-ci is
its first consumer and for no other reason. The design is the superproject's
`eventsourcing-plan.md`; what follows is what biting it feels like.

**THE EXTRACTION RULE: no `eu.wohlben.qits.ci.*` may be imported anywhere in that module, main or
test.** The whole value of the arrangement is that lifting it out is a `git mv` plus a pom, and one
import taken in the moment because the class was right there turns that into a refactor.
`ExtractionRuleTest` greps the module's own sources, so it fails a build rather than a review. The
event classes go the other way round: `ci-events/` depends on `eventsourcing/`, never the reverse,
and it is allowed the `ci` namespace precisely because it is qits-ci's vocabulary rather than the
library's.

Four things are easy to get wrong here:

- **The canonical form is a wire contract, not a formatting preference.** qits-events stores the
  `payload` string verbatim and compares it byte-for-byte to tell an idempotent replay (200) from a
  reused UUID (400), so two serializations of one event that differ by a space are a contradiction
  to the other side. `CanonicalJson` therefore builds its **own** `ObjectMapper` rather than
  injecting the CDI one — the consuming application's `ObjectMapperCustomizer`s must not be able to
  reach it — and sets every knob that could vary explicitly. Its class javadoc names each and why.
- **`eventId` is fixed at construction and never regenerated.** It is the `{id}` of the PUT, which
  is the only reason a retry is safe: a request whose response was lost replays as a 200 instead of
  writing the event twice. An event class may hold it as an ordinary record component; the library
  keeps everything `QitsEvent` declares out of the payload, so identity travels in the envelope.
- **The publisher's `HttpClient` is pinned to HTTP/1.1.** The JDK default is HTTP/2 with an `h2c`
  upgrade, and an upgrade carrying a request body **delivers that body twice** — measured against
  the test stub, once through the server's upgrade handler and again as an HTTP/2 data frame ninety
  milliseconds later. Idempotency made it harmless and therefore invisible; it was a doubled request
  on every publish. Do not drop the `version(...)` line.
- **The outbox is failure-path-only, and empty in a healthy process.** A publish that lands writes
  nothing; a row that is delivered on retry is deleted. So the row count is a health signal rather
  than a log, and the log is qits-events. The known hole — a crash between the inline attempt
  failing and the row committing — is named in `OutboxEvent`'s javadoc and deliberately left open.

Its own datasource, persistence unit and Flyway lineage (`eventsourcing`, `db/eventsourcing/migration`)
follow the platform convention, for the ordinary reason plus one more: the split out of this repo
should move files, not data.

### How the deployable uses it

`service/…/bus/` is the whole of qits-ci's wiring, and it is two beans and no configuration:
`BuildSuccessfulAnnouncer` publishes, `BuildSuccessfulListener` consumes, and the subscriber dials
itself on `StartupEvent` because a listener bean exists. Registering a listener really is "add a
bean" — no channel name, no annotation — and no `@Unremovable` is needed, because
`EventDispatcher`'s `Instance<QitsEventListener<?>>` is what ArC counts as a use.
`EventsourcingDarknessTest` asserts that rather than trusting it, since a removed listener
subscribes to nothing and says nothing about it.

**The publish hook hangs off a seam, and it is a *second* seam beside `CdNotifier` rather than a
widening of it.** `RunAnnouncer` (in `ci/control`, implemented in `service/`) is what keeps the `ci`
module free of the bus — the same reason the cd notifier is arranged that way — but the two ports
stay separate because they mean different things: cd is asked to deploy, the bus is told a build
passed. The one difference in the signature is `finishedAt`, which the event needs and cd does not,
and it comes back out of `finishRun` rather than off the `CiRun` instance: that method mutates a
freshly loaded entity in its own transaction, so the caller's copy never sees the value. **A null
`occurredAt` is a 400 from qits-events on every green build**, which is why the seam test asserts
the timestamp rather than only the coordinates.

The call sits on the single-threaded run worker and it blocks. That was the trade, and it is bounded
rather than free: `publish()` never throws, attempts the PUT inline, and gives up after
`qits.eventsourcing.publish-timeout` (~5s), after which the outbox owns delivery. So an unreachable
qits-events costs each green build a few seconds and nothing else. Anything slower than that does
not belong behind that port.

Two configuration facts about this module that are easy to get backwards:

- **The darkness belongs to `service/`, not to the library.** The jar ships
  `qits.eventsourcing.enabled=true` — a library that shipped dark is one whose first deployment
  discovers it was never wired up — and `service/src/main/resources/application.properties` carries
  the `%dev`/`%test` `false`, exactly as it does for the OTel keys. Nothing else about the bus is
  restated there: `qits.events.url`, the outbox datasource, the timeouts and the retry budget are
  ordinal-100 defaults in the jar, and a copy in the app's file would be a second place to change.
- **Dark does not mean absent.** `enabled=false` stops publishing, sweeping and dialling; it does
  not stop the datasource. Quarkus opens the connection and runs Flyway at boot regardless, so
  `service/src/test/resources/application.properties` points that datasource at in-memory H2 for the
  same reason it does for `ci` — measured, not assumed: without those lines the suite creates and
  migrates a real `~/.qits/data/eventsourcing`, and two builds on one host race for its
  single-writer file.

  **The deployment side of that same sentence cost a rollout, so it is worth stating plainly: adding
  this module to the deployable adds a MANDATORY deployment variable.**
  `QUARKUS_DATASOURCE_EVENTSOURCING_JDBC_URL` must point at the data volume, exactly as
  `QUARKUS_DATASOURCE_CI_JDBC_URL` already does. The shipped default interpolates `${user.home}`,
  which is the platform's convention and right for a host-run process — but in a container with no
  `HOME` the native binary resolves it to `?`, and H2 rejects a path implicitly relative to the
  working directory rather than falling back to one. The process then dies at Flyway before serving
  anything: `Failed to start quarkus` / `FlywaySqlUnableToConnectToDbException`. This is the third
  member of the family this file already names (the `AUTO_SERVER=TRUE` that killed the binary, the
  IPv4 bind, this) — **a config default no JVM test exercises, failing only in the packaged artifact
  in its real environment**. It fails loudly and safely, since cd's health gate keeps the previous
  container, but it fails.

`quarkus-scheduler` (the outbox sweeper's) arrives transitively with the jar and is new to this
deployable; `quarkus-websockets-next` was already here for the ci-daemon control plane, so the
client half costs the image nothing. `quarkus-undertow` stays absent — check it with the
`dependency:tree` line under "The Angular client" after touching this pom.

## Adding a dependency on another context

Don't. This context has no compile-time dependency on any other qits module and should not grow
one. Things arrive as an HTTP payload on the intake, or as a URL in config, or not at all. There is
no `RepositoryLookup`-style port here and there should be no need for one: a run knows a repo id, a
branch name and a sha, and everything else it wants it fetches from the git host itself.

Never add a JPA relation to another context's entity. `ci_run.repo_id` is a plain `String` column
in ci's **own** physical database; a foreign key cannot span it.

## Untrusted input

Four things reaching this code are attacker-controlled and must stay that way in your head:

- **The intake payload.** `/ci/api/events/` sits on the token-free allowlist and the token defaults
  to blank. `CiIdentifiers.require{RepoId,Branch,Sha}` validates all three *before* they reach a
  filesystem path or an argv. Never widen those, never bypass them, never interpolate an identifier
  into a shell string.
- **The step's `image`.** It comes from a file in the repository being tested and lands in the
  `docker run` argv as a positional argument, so it is checked in the same place and to the same
  standard: `CiIdentifiers.requireImage` rejects blank and anything starting with `-`. Deliberately
  loose otherwise — which registry hosts, tags and digests resolve is the registry's business. This
  is hardening rather than a fix: no exploit through it is known (`ProcessBuilder` never
  shell-splits, and the fixed trailing `-c <BOOTSTRAP>` tokens defeat the obvious re-parses), and
  "the argument parser will surely never take this for a flag" is not a claim worth re-defending.
- **The step script.** It is code from a repository, and **qits-ci never executes it.** No code
  path here runs repo-controlled code as a host process, and none runs it through `docker exec`. A
  script leaves this process as a field of one JSON frame, on a socket the step container's own
  daemon dialled outbound, and executes as that daemon's child inside a sandbox with
  `--cap-drop=ALL`, `no-new-privileges` and resource caps. qits-ci's whole docker
  vocabulary is container lifecycle — `run`, `logs`, `rm`, `ps`, `network inspect`/`create` — and
  `exec` is not in it, not even as a way to deliver the daemon binary. The only host processes this
  service spawns are that CLI and its own `git` against its own bare cache: ci tooling over
  ci-owned state, never pipeline content.

  `bash -c <anything from a repository>` appearing anywhere in this repo, in `src/main` or
  `src/test`, host-side or inside a docker argv, is the regression this paragraph exists to make
  unambiguous. The grep is `grep -rn "bash -c\|PRELUDE_FAILED\|docker exec"` over both modules; it
  must find nothing that executes.

  **"A step container never gets a docker socket" was this section's invariant and it is now false.
  What replaced it is narrower and was chosen deliberately, not conceded:** a step container never
  gets one *silently*. A step declares `docker: true` in `.config/qits/ci-post-receive.yml`, the
  launcher bind-mounts `qits.ci.docker-socket-path` for that step and no other, the config diff shows
  the declaration, and the run row records that step like any other. Such a step is
  **root-equivalent on the host** — the socket is the daemon and the daemon is root, so it can mount
  host paths, start privileged containers and leave the sandbox at will; the cap-drop flags stay on
  and fence the step's own process tree, which is not the same thing as bounding what the daemon will
  do on its behalf. Accepted for the POC under the standing posture (the sources are trusted;
  intra-network hardening is parked and will be addressed platform-wide), and it is why publishing is
  an ordinary step rather than a seam: an unprivileged builder later is a different step *image* that
  stops declaring the flag, and nothing here changes.

  **Every step that does not declare it keeps the sandbox exactly**, which is why
  `CiDaemonLauncherTest` asserts the mount's **absence** as hard as its presence and `CiDaemonGateIT`
  proves both against real containers in one run. Anything that would hand a step more privilege
  than that one declared mount — an undeclared socket, a host mount, a shared network with services,
  a relaxed cap — is a security change, not a convenience.
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
  changes: `./mvnw -pl service -am test -Dtest=OpenApiSchemaExportTest
  -Dsurefire.failIfNoSpecifiedTests=false`. Both extra flags are load-bearing: `-am` because the
  reactor's own modules are not installed anywhere, so `-pl service` alone cannot resolve them, and
  `failIfNoSpecifiedTests=false` because `-am` then walks the sibling modules, which have no test by
  that name.
  **The document holds exactly one path, and that is correct.** The intake and the two run reads
  carry `@Operation(hidden = true)` because they are machine surfaces rather than part of the JSON
  API the Angular client consumes, and the monorepo's own document omits them for the same reason —
  so for a long time this file was `paths: {}` and *that* was the right output. `POST
  /ci/api/runs/{runId}/cancel` is deliberately **not** hidden: it is the one operation here a person
  invokes on purpose, so it belongs in the document a client is generated from. The file is committed
  precisely so that hiding or unhiding an operation shows up as a diff.
  Note the test runs as a `@QuarkusTest` and indexes the test classpath, so a `@Path` resource under
  `src/test` would land in the document — that is why `IdentityEchoResource` is hidden too.
- A `Failed to start quarkus` / `Port already bound: 8081` failure is the known flake
  (`migration-plan.md` §9 item 14) — `@QuarkusTest` restarts racing for the test port. Re-run first.
  `CiPackagedSurfaceIT` is deliberately outside that race: failsafe passes it
  `quarkus.http.test-port=0`, so the packaged app it launches takes a free port instead of queueing
  behind whatever surefire has not finished releasing. `eventsourcing/`'s suite sets the same key in
  its own `src/test/resources/application.properties`, for a version of the same reason it can
  actually fix: that module registers no route at all — quarkus-websockets-next is there for its
  *client* — so the server a `@QuarkusTest` starts is incidental, and three test classes asking for
  three configurations means three restarts racing one port.
- **The eventsourcing suite talks to a real socket and a fake clock.** `StubEventsServer` is a
  `QuarkusTestResourceLifecycleManager` — a Vert.x server answering the real PUT and the real
  upgrade on an ephemeral port, handed to Quarkus as `qits.events.url` *before it boots*, which is
  the only way a port that cannot be known earlier reaches the application's config (qits-gateway's
  `StubUpstream`, same shape). It is deliberately dumb: it scripts status codes and records what
  arrived, and holds no idempotency table, because a second implementation of qits-events whose
  agreement with the first nobody checks is worse than no coverage. `TestClock` is a plain
  `Clock` bean that outranks the module's `@DefaultBean` producer, so the retry schedule's
  eighty-odd seconds are walked in milliseconds and `OutboxSweeper#sweep` is called rather than
  waited for. The scheduled tick is configured to 24h in the same file so it never lands in the
  middle of one.
- `CiPackagedSurfaceIT` is the only test that runs the **packaged artifact** — the fast-jar under
  `-DskipITs=false`, the binary under `-Dnative`. It is not a second boundary test and behaviour
  does not belong in it: it asserts the handful of things a `@QuarkusTest` structurally cannot see,
  because they only exist once the app is built (the routes' build-time prefixes, the shipped
  datasource URL, Flyway's migration surviving as a resource, SnakeYAML and Panache on a real run,
  and that `/ci/daemon` is on the artifact's router). Its pipeline declares no steps, so it needs no
  container; step execution stays in `CiDaemonGateIT`.
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
  `-DskipITs=false`, excluded from the `native` profile as every docker-backed IT here is and for the
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
- **Both `FakeCiStepRunner`s are scripted-event fakes, and neither performs a step.** A test
  declares the chunks a step "prints" and the `StepResult` it ends with; the fake replays that
  against the listener and returns it. No processes, no `bash`, no clone. The service module's copy
  used to be a deliberately *honest* fake that cloned and ran the script as host processes — that
  died with the approach it modelled, because a fixture that keeps executing repository code keeps
  the retired approach alive in the test sources after it left the main ones. Real step semantics are
  proven in exactly one place, `CiDaemonGateIT`, against a real container. The two fakes are
  duplicated on purpose — the modules do not share a test classpath.
  The ci module's copy also carries a `during(stepIndex, …)` hook: it runs something on the worker
  thread *while* a step is executing, which is how a cancellation arriving mid-step is staged with no
  sleep and no race about when "mid-step" is.
- **`BuildSuccessfulPublishTest` is the only class in this repo that runs with the event bus on.**
  Everything else inherits the shipped `%test` darkness, so "the suite dials nothing" is the default
  rather than an arrangement each test makes; that class turns it back on through a
  `QuarkusTestProfile` and points it at its own `StubEventsServer` — a trimmed second copy of the
  eventsourcing module's, duplicated for the reason both `FakeCiStepRunner`s are (the modules do not
  share a test classpath, and a test-jar to bridge forty lines is worse). It drives a real run to
  `SUCCESS` through the real intake and asserts the *wire* contract the other side was built
  against: one PUT per green run, a v4 UUID in the path, `name` as the signature, and the run's
  coordinates in the canonical payload. Retries, the outbox and the three-way PUT semantics belong
  to the eventsourcing suite; the round trip through a real qits-events belongs to the platform.
- `CiPipelineBoundaryTest` starts at the intake POST, not at a `git push`, because the git host is
  in qits-artifacts. Assertions about what the git host's hook does or does not send belong there.
- `CiDaemonGateIT` is **the** gate: the outline's whole lifecycle through the real intake path, on
  real containers. A two-step pipeline pushed into a real bare, the event POSTed, live chunks read
  off the `live` object mid-run, terminal per-step rows with host-stamped timestamps after, a
  cancellation honored mid-step, and a per-step timeout recorded as timed-out rather than failed. It
  deliberately includes a **noisy** step (`yes` for a second) — bounded memory under a chunk flood is
  a property of the relay, and the only place to prove it is against a daemon really producing them.
  It needs the same three things `CiDaemonHandshakeIT` needs (docker, the step image,
  `-Dqits.ci.daemon-binary=<path>`) plus the same host-networking route back to the JVM, and it
  carries the same caveat: on a host without that route it fails rather than skips. Do not "fix" that
  by weakening the assertions.
  It reaches the **injected** launcher rather than a hand-wired one, because the point is the
  production path — so it overrides the container-facing config through a `QuarkusTestProfile` whose
  `getConfigOverrides()` starts the fixture's server first (the port has to exist before the app
  boots), and unwraps the launcher's CDI proxy for the one value that cannot be known that early, the
  daemon url with this JVM's own test port in it. Both are commented in place; neither is a pattern
  to spread.
  It is tagged `extended`, and the `native` profile excludes that tag (`qits.it.excluded-groups` in
  the root pom) while flipping `skipITs`: a native build has to run the ITs to be worth anything,
  and this one would fail it for reasons that are about the host's docker and networking rather than
  the binary. `-DskipITs=false` still runs it, unchanged.
