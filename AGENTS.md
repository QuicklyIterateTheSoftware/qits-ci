# qits-ci — working notes

Read `README.md` first: it defines the boundary (what arrives over HTTP, what ci fetches for
itself) and the config surface. This file is the working conventions on top of it.

## The two rules that shape everything

**A clone builds against the platform Maven repository** — no monorepo and no prior `mvn install`.
`qits-eventstream:1.0.0` is resolved from local qits-artifacts; `qits-local-up.sh` publishes it before
building this service. `mvn verify` is the gate once that repository is available.

That is why: the poms duplicate versions instead of inheriting them, the suites stand up their own
bare git repos instead of using fixture submodules, the git host is a `file://` directory laid out
as `<base>/git/<repoId>`, and the one seam that needs real docker is faked (`FakeCiStepRunner`)
rather than skipped.

The Angular client at `service/src/main/webui` remains the sole submodule. Initialise it before an
image build; qits-eventstream is a normal Maven dependency and must not return as a gitlink.

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
  part of the change. The repo's one explicit registration is `EventWireReflection` in
  `service/…/bus/`, and it is worth reading as the worked example of this bullet: the types are
  ordinary records nobody had to think about until a hand-built `ObjectMapper` put them outside
  everything Quarkus scans. See "The event bus".
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
- `ci-events/` — the event classes qits-ci emits, `eu.wohlben.qits.ci.events`. Under this repo's own
  namespace because it *is* this repo's vocabulary; depends on `eventstream` and nothing else.

The **directories** are `ci/`, `service/`, `ci-daemon-protocol/` and `ci-events/`;
the artifactIds are `qits-ci-domain`, `qits-ci-service`, `qits-ci-daemon-protocol`,
`qits-eventstream` and `qits-ci-events`. The first two mismatch deliberately — the extracted git
history is anchored to the directory names, and generic coordinates like `eu.wohlben:ci` would
collide in the shared `~/.m2` that every workspace container mounts. `eventstream/` no longer
mismatches at all: the directory took the artifact's name when the module left, which is the whole
of what "eventsourcing" ever bought and it bought it badly — the module is an event *bus client*,
not an event-sourcing implementation, and nothing here or anywhere else has an event-sourced
aggregate. The old name is kept alive only by `eventsourcing-plan.md`, which is a historical
document and is not renamed.

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

**`eventstream/` is the sibling that shows what this module is not, and the contrast is the whole
justification for vendoring.** Both are another repository's code sitting in this tree, and both are
read-only from here — but that one is a **submodule**, so git owns the copy, a drift is impossible
by construction and an update is one `git submodule update --remote`. This one is a hand-made copy
kept honest by a `diff -r` and a shared test. The difference is not taste: `qits-eventstream` is a
maven module a reactor can build, and `qits-ci-daemon`'s protocol module is one module of a **go**
repository whose build this reactor cannot enter. Vendoring is what is left when a submodule would
give you the files but not a jar. If that ever stops being true — the day the protocol module is
published, or the day its repo grows a maven build — vendoring stops being the answer, and the
sibling directory is the template for what replaces it.

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
  from `CiRunService`'s own flag, never inferred from how the call came back. Between the launch and
  the first frame there is no step to cancel, so the launch is torn down instead, which completes the
  same await at once.

  **Cancelling a run that has not started at all never reaches this package**, and that is the shape
  a `QUEUED` row bought. `CiRunService.cancel` finds the row still queued, writes it `FAILED` in its
  own transaction, and the worker's claim then sees a row that is no longer `QUEUED` and drops it —
  no container, no `Cancel` frame, no launch to tear down. The flag is still raised, and that is not
  belt-and-braces for its own sake: cancel runs on the request thread and the claim runs on the run
  worker, so if the worker won the race and turned the row `RUNNING` in between, the flag is what
  stops the run before its first container. Neither thread has to win for the answer to be right.

## The run queue, and what a run row means

**A run is a row from the moment it is accepted.** `CiRunService.onPostReceive` and
`onEventTrigger` both `INSERT` a `QUEUED` row before they return; the worker's first act is
`startQueued`, which flips it to `RUNNING` inside its own transaction *and reads the status back in
the same transaction*, so a run that was cancelled while it waited is never picked up. Everything
from there down is unchanged.

**This revised the recording rule on purpose, and the old wording is worth having in front of you.**
It read: *a run is only ever recorded when it says something true about a commit* — which was a
statement about when the `INSERT` happens, and it is what made the queue invisible. It now reads:

> **A run row exists from the moment the work is accepted, and it is removed again if it turns out to
> describe nothing that happened.**

What a *finished* worker leaves behind is unchanged outcome for outcome. The three cases that
recorded nothing still record nothing — no config file (opt-in), a commit force-pushed away, a git
host that could not be reached — but they reach that by **discarding** a row that already exists
rather than by never writing one, through the same `discardRun` the mid-run `SHA_GONE` backstop uses.
`CONFIG_ERROR` and the green/red outcomes finish the accepted row instead of inserting a second one.
The only observable difference is a transient `QUEUED` row in between, which `GET
/ci/api/runs/active` and, briefly, a repository's own listing will show.

Two of those three deserve their reasoning restated, because "there is a row, why not keep it" is the
tempting wrong answer. A repository that declares no pipeline must not accumulate a row per push, or
opt-in stops meaning anything. And an unreachable git host must not leave a red row: **a read failure
must not invent a gate**, and a red row is exactly an invented gate — the commit is very likely fine
and this process simply could not ask.

**`QUEUED` survives a restart.** `sweepInterrupted` re-enqueues those rows, oldest first, so a
redeploy landing between acceptance and execution no longer eats either a push-triggered or an
event-triggered build. `RUNNING` still dies: those rows are marked `FAILED`, because their in-flight step went with the process and the launch
table is memory. Nothing here adds durability beyond the row, which is what keeps the rest of the
restart story free.

An event run stores the original timestamp, canonical payload and exact trigger-file content on its
row. Recovery reparses that immutable snapshot: it neither reads a moved branch nor depends on the
live-only event stream redelivering an occurrence.

**`onStart` skips test mode, so `sweepInterrupted` is package-private and the suite drives it.** A
claim about a restart is made by seeding the rows a dead process would have left and calling it —
`CiQueuedRunTest` does exactly that, including the ordering, which is `createdAt` because the worker
is FIFO and a restart must not reorder a backlog.

## Addressing

`README.md` has the shape; two things bite when you change a path here.

**`quarkus.rest.path=/ci/api` lives in `service/src/main/resources/application.properties` and the
suite inherits it.** So a resource's `@Path` is relative to `/ci/api` and must never repeat `ci`.
Tests address the absolute path, which is what makes them catch a prefix regression.

**`/ci/api/runs/active` and `/ci/api/runs/finished` sit under `/ci/api/runs/{runId}`, and only
JAX-RS' sorting rule keeps them apart.** A literal segment outranks a template, so the listing wins —
but a regression there would show up as the client's rail 404ing and nothing else, so
`CiPipelineBoundaryTest` asserts each route resolves to the listing envelope rather than to a lookup
for a run named `active` or `finished`. Same for `/ci/api/repositories/summary` under
`/ci/api/repositories`, which is the easier case (that one has no template to lose to). None of them
adds a literal Vert.x route, so `quarkus.quinoa.ignored-path-prefixes` is unchanged — `/api` already
covers them.

**The two run listings are complements, and the predicate is written that way on purpose.**
`/active` is `status in (QUEUED, RUNNING)` and `/finished` is `status NOT in (QUEUED, RUNNING)` —
not `in (SUCCESS, FAILED, CONFIG_ERROR)`, which reads the same today and rots silently: a sixth
value added to `ck_ci_run_status` would be finished in fact and invisible to both lists, so a run
would leave one and never arrive in the other. Written as a complement they partition the table by
construction. `/finished` carries `?limit=` where `/active` does not, and the asymmetry is the whole
difference between them: what is active is bounded by what one single-threaded worker has accepted,
what is finished grows with the instance's uptime. Absent means **5**, not unbounded — the opposite
of the repository listing's default, because there is no repository here to make "all of them" a
bounded question — and an ask above **100** is clamped rather than refused, since this is the one
listing that is both unscoped and otherwise unbounded.

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
packaged artifact, not by surefire — concretely, by `CiPackagedSurfaceIT`'s five probes (the
segment, a deep link, the bare-segment redirect, and the two paths that must answer 404 rather than
the client). Every claim in this section is a measurement that test now repeats.

## The event bus

`eventstream/` is the [qits-eventstream](https://github.com/QuicklyIterateTheSoftware/qits-eventstream)
repository, a **submodule** — the platform's event bus client (`QitsEvent`, `QitsEventBus.publish`,
`QitsEventListener`, `QitsRawEventListener`, `CausationScope`). It used to be a directory here, a
library waiting to move out; it has moved, and qits-ci is now an ordinary consumer that happens to
build it in the same reactor. The design is the superproject's `eventsourcing-plan.md` and
`event-causation-plan.md`.

**Its rules are in its own repository and are not restated here.** Read `eventstream/AGENTS.md`
before changing anything about how this service publishes or listens; the six that bite are the
canonical form as a wire contract, `eventId` fixed at construction, the HTTP/1.1 pin, an outbox that
is empty in a healthy process, causation stamped in the envelope by the bus alone, and the typed vs
raw consuming seams with their subscribe-frame union. A second copy of any of that in this file is a
copy that will drift.

**The extraction rule is now a repository boundary rather than a test.** It read "no
`eu.wohlben.qits.ci.*` may be imported in that module"; the module is a different repo with a
different clone-alone gate, so the rule enforces itself and `ExtractionRuleTest` travels with it.
What is left on this side is the arrow: `ci-events/` depends on `eventstream/`, never the reverse,
and it keeps the `ci` namespace precisely because it is qits-ci's vocabulary rather than the
library's.

**Editing the submodule from here is the mistake to avoid**, for the same reason as
`ci-daemon-protocol/` though by a different mechanism: this checkout is a real branch and a commit
made in it is a commit in *that* repository, pushed to *that* remote. Land the change there, push
it, and let the sync move this checkout. A version bump is not part of it — the pom is
`1.0.0-SNAPSHOT` and parentless, and the reactor resolves it as a module in place.

What remains qits-ci's, and is documented below: `service/…/bus/` (both ends of the wiring), the
`RunAnnouncer` seam that keeps `ci/` free of every eventstream type, `EventWireReflection` (the
native-image registration, which lives with the deployable rather than with the library), and the
`%dev`/`%test` darkness. The trigger engine's half is under "The trigger engine".

### How the deployable uses it

`service/…/bus/` is the whole of qits-ci's wiring, and it is two beans and no configuration:
`BuildSuccessfulAnnouncer` publishes, `BuildSuccessfulListener` consumes, and the subscriber dials
itself on `StartupEvent` because a listener bean exists. Registering a listener really is "add a
bean" — no channel name, no annotation — and no `@Unremovable` is needed, because
`EventDispatcher`'s `Instance<QitsEventListener<?>>` is what ArC counts as a use.
`EventstreamDarknessTest` asserts that rather than trusting it, since a removed listener
subscribes to nothing and says nothing about it. **A `QitsRawEventListener` is registered the same
way and survives removal for the same reason** — `Instance<QitsRawEventListener>` is a second
injection point of the same kind — and the eventstream suite proves it the same way too, with a
raw listener that is injected nowhere and whose signature (a name no `eventType()` produces) has to
turn up in the subscribe frame.

**The publish hook hangs off a seam, and it is a *second* seam beside `CdNotifier` rather than a
widening of it.** `RunAnnouncer` (in `ci/control`, implemented in `service/`) is what keeps the `ci`
module free of the bus — the same reason the cd notifier is arranged that way — but the two ports
stay separate because they mean different things: cd is asked to deploy, the bus is told a build
passed. The one difference in the signature is `finishedAt`, which the event needs and cd does not,
and it comes back out of `finishRun` rather than off the `CiRun` instance: that method mutates a
freshly loaded entity in its own transaction, so the caller's copy never sees the value. **A null
`occurredAt` is a 400 from qits-events on every green build**, which is why the seam test asserts
the timestamp rather than only the coordinates.

**There are now two publishing seams and they are separate for the same reason `CdNotifier` and
`RunAnnouncer` are.** `ReleaseAnnouncer` (`ci/control`, implemented by
`service/…/bus/SoftwareReleaseAnnouncer`) announces one published *artifact*; `RunAnnouncer`
announces a run that passed. One green run can go down both, and a release pipeline's does — first
`BuildSuccessful`, then one `SoftwareRelease` per declared artifact. Folding them would put "the
build passed" and "the registry has it" behind one name, which is precisely the conflation the
split-release redesign exists to undo: the old single event fired at release-*push* time and every
consumer read it as "the package exists", with an entire upstream build in the gap.

Three things about that second seam are worth having in front of you:

- **The fan-out is `CiRunService`'s and the port takes one artifact.** N declarations are N calls, so
  a failure costs one announcement rather than the rest. The bus already supports siblings —
  the outbox enqueues one row per event in its own transaction and `CausationScope.current()` is a
  non-consuming read — and `CiEventTriggerCausationTest` asserts the parent lands on *both* of two
  siblings rather than trusting that.
- **`CausingEvent.parentOf` is one implementation, deliberately.** Both announcers turn the run row's
  `triggerEventId` into the published event's parent, and it is defensive: an id that will not parse
  costs the run its causation edge and nothing else, because throwing there would lose the
  announcement for the sake of the edge.
- **The version is read at completion, not at accept.** It comes out of the triggering event's
  payload, which is on no column and exists only in the worker's closure — so it travels down into
  `runSteps` with the declaration. Reading it later is what lets a red run announce nothing *and*
  warn about nothing; a declaration whose trigger carries no `version` is a WARN and no event, since
  a blank version would publish a package reference nothing can resolve.

The call sits on the single-threaded run worker and it blocks. That was the trade, and it is bounded
rather than free: `publish()` never throws, attempts the PUT inline, and gives up after
`qits.eventstream.publish-timeout` (~5s), after which the outbox owns delivery. So an unreachable
qits-events costs each green build a few seconds and nothing else. Anything slower than that does
not belong behind that port.

Two configuration facts about the bus that are easy to get backwards, and both are this repo's
to get right rather than the library's:

- **The darkness belongs to `service/`, not to the library.** The jar ships
  `qits.eventstream.enabled=true` — a library that shipped dark is one whose first deployment
  discovers it was never wired up — and `service/src/main/resources/application.properties` carries
  the `%dev`/`%test` `false`, exactly as it does for the OTel keys. Nothing else about the bus is
  restated there: `qits.events.url`, the outbox datasource, the timeouts and the retry budget are
  ordinal-100 defaults in the jar, and a copy in the app's file would be a second place to change.
- **Dark does not mean absent.** `enabled=false` stops publishing, sweeping and dialling; it does
  not stop the datasource. Quarkus opens the connection and runs Flyway at boot regardless, so
  `service/src/test/resources/application.properties` points that datasource at in-memory H2 for the
  same reason it does for `ci` — measured, not assumed: without those lines the suite creates and
  migrates a real `~/.qits/data/eventstream`, and two builds on one host race for its
  single-writer file.

  **The deployment side of that same sentence cost a rollout, so it is worth stating plainly: adding
  this module to the deployable adds a MANDATORY deployment variable.**
  `QUARKUS_DATASOURCE_EVENTSTREAM_JDBC_URL` must point at the data volume, exactly as
  `QUARKUS_DATASOURCE_CI_JDBC_URL` already does. The shipped default interpolates `${user.home}`,
  which is the platform's convention and right for a host-run process — but in a container with no
  `HOME` the native binary resolves it to `?`, and H2 rejects a path implicitly relative to the
  working directory rather than falling back to one. The process then dies at Flyway before serving
  anything: `Failed to start quarkus` / `FlywaySqlUnableToConnectToDbException`. This is the third
  member of the family this file already names (the `AUTO_SERVER=TRUE` that killed the binary, the
  IPv4 bind, this) — **a config default no JVM test exercises, failing only in the packaged artifact
  in its real environment**. It fails loudly and safely, since cd's health gate keeps the previous
  container, but it fails.

  **The fourth member is not a config default at all, and it is the one that failed quietly.**
  `service/…/bus/EventWireReflection` is a class with no code: a `@RegisterForReflection` naming
  `BuildSuccessful`, `SoftwareRelease`, `EventEnvelope`, `EventFrame` and the
  `CanonicalJson$QitsEventMixin`, plus a
  private constructor. Without it the deployed binary threw Jackson's `No serializer found for class
  … BuildSuccessful … you may need to configure reflection` on **every** green build — inside
  `CanonicalJson` and therefore *before* an envelope existed, so the event never reached the outbox
  either. Not a delayed delivery, a lost one, with a single WARN per run to say so.

  Nothing registered them because **`CanonicalJson` builds its own `ObjectMapper` on purpose**
  (above, and not negotiable): the graph that mapper serializes is invisible to the build step that
  scans for what needs reflecting on. **The mix-in is in the list because two binaries were built to
  find out, and it is the worse of the two failures.** Jackson reads its `@JsonIgnore`s with
  `getDeclaredMethods()`; with the three record types registered and the mix-in left out, a green
  build published `{"branch":…,"commitSha":…,"eventId":"00a32ad6-…","finishedAt":…,"repoId":…,
  "runId":…}` — no crash, no log, `eventId` simply present in a payload that is supposed to carry no
  identity at all. A wire contract violation that breaks nothing visible is not a lesser bug than one
  that throws. Register; do not "fix" a recurrence by injecting the CDI mapper.

  It lives in `service/` rather than beside the code it describes for the reason everything native
  does: the deployable is what gets built into an image, and `eventstream/` is another repository
  entirely.

  **The mix-in is named as a STRING, and that is the one line in this repo a rename of the library
  cannot break loudly.** `classNames = "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin"`
  compiles whatever it says; a stale package there costs the payload its `@JsonIgnore`s in the
  binary and nothing else. `EventWireReflectionTest`'s `MIXIN` constant is the guard — it resolves
  the same string with `Class.forName` — so the two move together or the suite goes red. `EventWireReflectionTest` guards the list's **completeness** — every
  listener bean's event type is in it, the mix-in's name still resolves — and says in its own javadoc
  that completeness is all a JVM test can guard, because on a JVM these classes reflect whether
  anyone registered them or not. The correctness proof is the binary, running: the round trip through
  a real qits-events.

  **That proof is cheap enough to repeat before a rollout, and it is how both facts above were
  established.** `sdk env` then `./mvnw package -Dnative -DskipTests`, run `service/target/qits-ci`
  with `QITS_EVENTSTREAM_ENABLED=true`, `QITS_EVENTS_URL` pointed at any process that answers a PUT
  with a 201, and push `steps: []` through the intake — a zero-step pipeline reaches SUCCESS with no
  docker and no daemon, which is the shortest path there is from a fresh binary to a published event.
  Read the PUT body. Anything about this module that only the binary can be wrong about is one minute
  of native-image away from being known rather than believed.

  **The far end of that failure was mute, and that is fixed too.** `EventDispatcher` logged a frame
  it could not read at DEBUG, so a binary that could not deserialize `EventFrame` would have consumed
  the entire stream in silence for as long as it ran. It is a WARN now, naming the frame's `name` and
  `id` when the text is JSON at all (a second, untyped read — `readTree` needs no reflection, which
  is precisely why it still works when binding does not). An unknown *signature* stays DEBUG: that
  one is ordinary traffic, since a subscription set is a filter rather than a promise.

`quarkus-scheduler` (the outbox sweeper's) arrives transitively with the jar and is new to this
deployable; `quarkus-websockets-next` was already here for the ci-daemon control plane, so the
client half costs the image nothing. `quarkus-undertow` stays absent — check it with the
`dependency:tree` line under "The Angular client" after touching this pom.

## The trigger engine

There are **two** trigger types now, and the second one is the reason the raw-listener seam exists.
A repository commits `.config/qits/ci-event-<anything>.yml` naming a domain event and a selection
over its payload; a matching event on the bus runs that file's pipeline against the head of `main`.
The design is the superproject's `ci-event-triggers-plan.md`, the format is `README.md`, and what
follows is what biting it feels like.

- **`ci/` stays free of the bus, in both directions.** `service/…/bus/CiEventTriggerListener` is the
  `QitsRawEventListener` bean; it turns an `EventFrame` into `CiEventTriggerService.Arrival`, four
  plain strings, and hands it over. That is the same seam shape `RunAnnouncer` is on the publishing
  side, pointed the other way, and it is why `CiEventTriggerService` — which does the real work —
  imports no `eu.wohlben.qits.eventstream` type. Keep it that way; the extraction rule protects
  the library, and this one protects the domain.
- **`signatures()` is `Set.of(ALL)` permanently, and it is not laziness.** The wire set is derived
  only when the connection is opened and **the subscriber does not dial at all when the union is
  empty**, so a listener that answered `Set.of()` until it had read some config would never open the
  stream it would read config over. `"*"` is the seam's documented idiom for exactly this, and the
  cost is that this deployable's subscribe frame is literally `["*"]` —
  `CiEventTriggerCausationTest` reads it off the stub to keep that from being a belief.
- **Three threads, and each boundary is deliberate.** `onFrame` runs on the bus's websocket worker,
  one frame at a time for *every* consumer, so it only enqueues. Evaluation runs on its own
  single-threaded `ci-trigger-worker`: not the dispatch thread because it does a `git fetch` per
  candidate repository, and **not `ci-run-worker`** either, though that is the obvious reuse — that
  thread is inside a running pipeline for minutes, and an event evaluated when the build ends is
  evaluated against a `main` that has moved. Single-threaded because two evaluations of one
  repository would race on the same bare cache. The queue is bounded; a full one is a WARN naming
  the event that was not evaluated, which beats turning a burst into heap.
- **The causation edge crosses those threads as data, not as context.** `CausationScope` is a plain
  `ThreadLocal` and does not follow work — that is its design, not its limitation. So the frame's
  `id` is written to `ci_run.trigger_event_id`, read back off the row at `announceRun`, and passed to
  `publish(event, parent)` as an explicit argument, which outranks the ambient context precisely for
  this case. It survives a restart, which no context could. Note it is the frame's `id` and never its
  `parentId`: the arriving event causes this run, its own parent is the previous hop's business.
- **The dedupe is a database constraint and the `NULL` behaviour is load-bearing.** `unique
  (trigger_event_id, repo_id, config_path)` is the at-most-one-run-per-(event, trigger file)
  guarantee, and it has to be a constraint rather than a check because what it survives is a race and
  a restart. **Every post-receive run has a null `trigger_event_id`** with the same repo and the same
  config path as the last one, so a database treating those as duplicates would break the second push
  to every repository — SQL says rows collide only when all corresponding values are non-null and
  equal, and `CiEventTriggerDedupeTest` pins that H2 agrees rather than trusting it. The constraint
  kills replays, not descendants, which is why it is **no loop guard**: see the footgun in
  `README.md`, and note that nothing here is built that the future DAG feature would have to undo.

  **It fires at accept now**, since the run row is written by `onEventTrigger` before it returns
  rather than by the worker later. Nothing about the semantics moved with it: a redelivery still hits
  the constraint and is still dropped as already-triggered, just on `ci-trigger-worker` instead of
  `ci-run-worker`, and before a queue slot is spent rather than after. The `NULL` behaviour is
  untouched — post-receive rows still carry a null `trigger_event_id`, they are just inserted a few
  milliseconds earlier in the life of a push.
- **The trigger file parser is strict where `ci-post-receive.yml` is lenient**, and the asymmetry is
  the point rather than an inconsistency. In a pipeline an unread key costs a feature; in a
  *selection* it costs correctness, because an absent `when:` means **unconditional** — so a mistyped
  `wehn:` would silently widen the trigger to every event of that name. Unknown top-level keys and
  duplicate keys are therefore errors in a trigger file and are not in a pipeline. The `steps:`
  schema is shared verbatim (`CiConfigSchema`), because a step must not mean two things.
- **`artifacts:` is the one key the trigger file adds rather than subtracts**, and it is what makes a
  file a *release pipeline*: a non-empty list of `{type: npm|maven|docker, name: …}`, strict in every
  direction (empty list, unknown type, blank name, extra key, wrong shape — all parse errors naming
  the file). It is a parse error in `ci-post-receive.yml` for its own reason rather than by symmetry
  with `branches:`: what a declaration announces is the *triggering* event's version, and a push
  carries none, so the key could only ever be inert there. The declaration is a **claim**, never an
  observation — qits-ci cannot see what a step pushed — and it is declared rather than emitted
  because a declaration is statically readable, which is the whole of what the parked cycle-detection
  work needs. The daemon's return channel could not have carried an emission anyway: it is
  `StepChunk` and `StepFinished`, and a stdout sentinel is forbidden by design.
- **The candidate list is the feature's one acknowledged compromise.** qits-artifacts owns the
  repositories and deliberately exposes no listing of them, so `KnownCiRepos` answers with what
  qits-ci already knows: recorded runs' repo ids plus its own bare caches. A repository that has
  never pushed cannot event-trigger until it does. It is one method behind `CiCandidateRepos` so the
  day a listing exists the swap is one class — and `FakeCandidateRepos` exercises that swap rather
  than leaving it a claim.
- **Nothing here needed native-image registration**, and `EventWireReflection`'s javadoc says why in
  full: SnakeYAML's `SafeConstructor` produces plain collections, the parser builds its records by
  hand, and the payload is `readTree`'d into a `JsonNode` and walked. No binding, no reflection, no
  fifth member of the family this file names. Check that reasoning again if the engine ever gains a
  Jackson `readValue`.

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

`V3__event_triggers.sql` is worth reading as the worked example of backfilling a `not null` column
into a live table: add it **with a default**, so every existing row is written correctly by the
`alter` itself, then `drop default`, so a future insert that forgets the value fails loudly rather
than getting a silent one. Its unique constraint is the trigger engine's at-most-once guarantee and
its `NULL` behaviour is load-bearing — see "The trigger engine".

**`V4__queued_runs.sql` is the worked example of the other kind: widening a check constraint the
original script never named.** V1 declared the status domain inline — `status varchar(32) not null
check (status in (...))` — so H2 generated the name, and there is no portable way to drop an
anonymous constraint. Three things came out of that and none of them should be undone:

- **The generated name was measured, not guessed.** `CONSTRAINT_76`, on a database created by V1 and
  on this platform's live `~/.qits/data/ci/h2/ci.mv.db` alike; H2 derives it from an object counter
  and V1 is the same script everywhere. Reproduce it with `java -cp <h2.jar> org.h2.tools.RunScript`
  over the lineage and `select constraint_name, constraint_type from
  information_schema.table_constraints where table_name='CI_RUN'`.
- **The replacement is named** (`ck_ci_run_status`), so the next widening is one line with nothing to
  measure.
- **The migration then writes one `QUEUED` row and deletes it**, and that probe is the load-bearing
  part. A database whose V1 check landed under a different generated name would take the drop as a
  no-op, add the named constraint beside the surviving one, and reject **every** accepted run at
  insert — silently in every JVM test, loudly only in the deployment. That is precisely this repo's
  worst failure family (the `AUTO_SERVER=TRUE` that killed the binary, the eventstream datasource
  url). The probe turns it into a Flyway failure at boot with the offending constraint named in the
  error, and cd's health gate keeps the previous container. Verified by renaming the constraint in a
  scratch database and watching V4 refuse to apply.

Add a status value ⇒ add a line to `ck_ci_run_status`. The enum's `@Enumerated(EnumType.STRING)` is
not the guard; the constraint is, and it will reject what the enum happily writes.

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
  **The document holds the read surface and the one write, and exactly one operation is hidden.**
  The criterion has always been "does a first-party client consume it, does a person invoke it" —
  machine surfaces stay out. For a long time that left `paths: {}`, then one path (`POST
  /ci/api/runs/{runId}/cancel`, the one operation here a person invokes on purpose), because no
  client read anything. **qits-spa-ci changed the answer, not the criterion**: it reads `GET
  /ci/api/repositories`, `GET /ci/api/repositories/summary`, `GET /ci/api/runs`, `GET
  /ci/api/runs/active`, `GET /ci/api/runs/finished` and `GET /ci/api/runs/{runId}` on every page it
  draws, so those are the JSON
  API a first-party client consumes and none of them carries `@Operation(hidden = true)`.
  Keeping them hidden would have meant this file omitted the entire contract that client depends on,
  and a breaking change to `CiRunDto` would have landed with an **empty diff** — which is the exact
  opposite of why the file is committed. `POST /ci/api/events/post-receive` stays hidden and the
  criterion is why: it is token-guarded, machine-only, and its wire contract lives in qits-artifacts.
  The file is committed precisely so that hiding or unhiding an operation shows up as a diff.

  **`?limit=` on the run listing binds as a `String` on purpose.** JAX-RS answers a *query*-parameter
  conversion failure with a **404** (the spec says so for `@QueryParam`, `@PathParam` and
  `@MatrixParam` alike), so an `Integer limit` would answer `?limit=abc` with "no such resource"
  instead of "bad request". The parameter is parsed in the resource and rejected through
  `CiExceptionMapper`'s `{"message": …}` envelope like every other bad input here; the OpenAPI
  document still declares it `integer, minimum 1` via `@Parameter`, because the document describes
  the contract rather than the binding.
  Note the test runs as a `@QuarkusTest` and indexes the test classpath, so a `@Path` resource under
  `src/test` would land in the document — that is why `IdentityEchoResource` is hidden too.
- A `Failed to start quarkus` / `Port already bound: 8081` failure is the known flake
  (`migration-plan.md` §9 item 14) — `@QuarkusTest` restarts racing for the test port. Re-run first.
  `CiPackagedSurfaceIT` is deliberately outside that race: failsafe passes it
  `quarkus.http.test-port=0`, so the packaged app it launches takes a free port instead of queueing
  behind whatever surefire has not finished releasing. `eventstream/`'s suite sets the same key in
  its own `src/test/resources/application.properties`, for a version of the same reason it can
  actually fix: that module registers no route at all — quarkus-websockets-next is there for its
  *client* — so the server a `@QuarkusTest` starts is incidental, and three test classes asking for
  three configurations means three restarts racing one port.
- **The eventstream suite is the submodule's, and its conventions are documented there.** It runs in
  this reactor and its failures land in this build, which is the only reason it is mentioned here:
  when it goes red, read `eventstream/AGENTS.md` under "The suite" rather than debugging it from
  this side, and fix it in that repository. The one fact worth carrying across is that its
  `StubEventsServer` is where this module's trimmed copy came from — see the bus test bullet below.
- `CiPackagedSurfaceIT` is the only test that runs the **packaged artifact** — the fast-jar under
  `-DskipITs=false`, the binary under `-Dnative`. It is not a second boundary test and behaviour
  does not belong in it: it asserts the handful of things a `@QuarkusTest` structurally cannot see,
  because they only exist once the app is built (the routes' build-time prefixes, the shipped
  datasource URL, Flyway's migration surviving as a resource, SnakeYAML and Panache on a real run,
  that `/ci/daemon` is on the artifact's router, and — the same argument, applied to Quinoa — **how
  the client and the machine surface divide `/ci`**). Its pipeline declares no steps, so it needs no
  container; step execution stays in `CiDaemonGateIT`.
  The SPA probes are qits-events' list (`docs/project-setup-quinoa-angular.md`), and closing that
  asymmetry was overdue: `/ci/` serves 200 HTML carrying the client's own `<base href="/ci/">`, a
  deep link (`/ci/runs/anything`) falls back to `index.html` so the Angular router owns it across a
  reload, `/ci` redirects 301, and `/ci/api/nope` and a plain `GET /ci/daemon` each answer 404 and
  **not the client**. The assertion is "not the client" rather than "never HTML" because what comes
  back is Vert.x' own stock `<h1>Resource not found</h1>`, which is `text/html` and correct — pinning
  the content type alone would fail against the right behaviour and still pass against the wrong one,
  since `index.html` is `text/html` too. All five are invisible to surefire by construction (Quinoa
  is off in test mode), which is exactly why they belong here.
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
  Both copies carry a `during(stepIndex, …)` hook: it runs something on the worker thread *while* a
  step is executing, which is how a cancellation arriving mid-step is staged with no sleep and no
  race about when "mid-step" is. It is also how `QUEUED` is staged at all — the run worker is
  single-threaded, so a run parked inside its first step really does hold the next one in the queue,
  and both states are then real at one instant the test controls. That is why the service module's
  copy grew the hook too: `RUNNING` and `QUEUED` had to be observable over HTTP, which is where the
  SPA sees them.
- **Two classes in this repo run with the event bus on, and they share one profile deliberately.**
  Everything else inherits the shipped `%test` darkness, so "the suite dials nothing" is the default
  rather than an arrangement each test makes; `BuildSuccessfulPublishTest` turns it back on through a
  `QuarkusTestProfile` and points it at its own `StubEventsServer`, and
  `CiEventTriggerCausationTest` reuses **that same profile class** rather than declaring an identical
  one — a second `@TestProfile` is a second Quarkus start, and these two want the same application.
  The second class is the trigger engine's bus half: a real frame through `EventDispatcher`, a real
  `git ls-tree` of a real bare, and the `parentId` on the PUT the triggered run publishes — the
  platform's first automatic causation edge, which nothing in the `ci` module can see. It is where
  the release **fan-out** is proved too, and that belongs here rather than at the seam: N
  `SoftwareRelease` events under one parent is what shows the stamp is a non-consuming read rather
  than something the first publish spends, and their payload bytes are asserted whole because they
  are the contract every downstream release pipeline reads. It is also
  where the stub's recorded **subscribe frame** is asserted to be `["*"]`; subscribes are not cleared
  by `reset()`, because there is one per connection and the connection outlives every test method.

  The stub they share is a trimmed second copy of the eventstream module's, duplicated for the
  reason both `FakeCiStepRunner`s are (the modules do not share a test classpath, and a test-jar to
  bridge forty lines is worse). `BuildSuccessfulPublishTest` drives a real run to `SUCCESS` through
  the real intake and asserts the *wire* contract the other side was built against: one PUT per green
  run, a v4 UUID in the path, `name` as the signature, and the run's coordinates in the canonical
  payload. Retries, the outbox and the three-way PUT semantics belong to the eventstream suite; the
  round trip through a real qits-events belongs to the platform.

  **One thing bites in both, and it is the shared candidate list.** Every repository either class has
  ever seeded is a candidate for every frame the trigger engine evaluates, for the life of that
  Quarkus instance. So a trigger file in a test fixture must select something **unique to the
  repository that committed it**, or one test method's event fires an earlier method's repository and
  "exactly two runs, exactly two publishes" stops being a statement about the test making it.
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
