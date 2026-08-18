# qits-ci — working notes

Read `README.md` first: it defines the boundary (what arrives over HTTP, what ci fetches for
itself) and the config surface. This file is the working conventions on top of it.

## The two rules that shape everything

**A clone builds against the platform Maven repository** — no monorepo and no prior `mvn install`.
`qits-eventstream:1.0.0` is resolved from local qits-artifacts; `qits-local-up.sh` publishes it before
building this service. `mvn verify` is the gate once that repository is available.

That is why: the poms duplicate versions instead of inheriting them, the suites stand up their own
bare git repos instead of using fixture submodules, the git host is `StubGitHost` — a real HTTP
server in the suite, serving those bares as `<base>/git/<repoId>` — and the one seam that needs real
docker is faked (`FakeCiStepRunner`) rather than skipped.

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
  that line before believing it proved anything. (This context used to shell out to `docker` at
  *runtime* too, which made the word useless as a signal in a log; it does not any more — every
  container is qits-containers' — so `docker` in a build log is now about the build. Look for the
  line anyway.)
- **Every dependency is a decision about what the builder has to be told.** Reflection, dynamic
  proxies, `ServiceLoader`, resources loaded by computed name and JNI/JNA all need registering, and
  when they are missing the failure lands at *runtime, in the binary*, while the JVM suite stays
  green. Prefer what is already in the image — `java.net.http` over a REST client library, which is
  why every client in `service/…/githost` is hand-rolled and why qits-containers' client jar is one
  too — and `java.lang.foreign` over JNA. (The older half of this bullet was `ProcessBuilder` over a
  process library, "which is why `CiProcess` shells out rather than links a docker client".
  `CiProcess` is **deleted**: this service spawns no process at all now, so the cheapest process
  library is no process library.) If a native build needs configuration to pass, that configuration
  is part of the change. There are TWO explicit registrations — `bus/EventWireReflection` and
  `containers/ContainersWireReflection`, one per jar that builds its own `ObjectMapper` — and the
  first is worth reading as the worked example of this bullet: the types are
  ordinary records nobody had to think about until a hand-built `ObjectMapper` put them outside
  everything Quarkus scans. See "The event bus".
- **So is every config default the app boots with.** `quarkus.datasource.ci.jdbc.url` carried
  `AUTO_SERVER=TRUE` out of the monorepo; it asks H2 to start its own TCP server, whose classes are
  not in the image, and the binary died at boot on a default no JVM test ever used. It was dropped
  rather than registered. That URL has since gone entirely — **the store is PostgreSQL, reached
  through the platform's generic resource contract** (`${QITS_RESOURCE_DB_URL}` and its two
  siblings), with no default behind it at all, so the whole class of "a default only the packaged
  artifact ever finds out about" is closed rather than fixed. `CiPackagedSurfaceIT` remains the
  guard, and it hands the launched artifact the ENVIRONMENT VARIABLES rather than the datasource
  keys, precisely so that the shipped expressions stay the ones under test.

## Package and module conventions

`eu.wohlben.qits.ci.*`, split across maven modules with disjoint sub-packages so there is no split
package:

- `ci/` — `entity`, `persistence`, `dto`, `mapper`, `control` and `error`. There is no `migration`
  package any more: the one Flyway migration that had to be Java went with the H2 lineage it was
  answering — see "Schema changes". Framework-free in the sense
  that matters: no JAX-RS, no websockets. Entities are Panache; mappers are MapStruct
  `@Mapper(componentModel = "jakarta")`.
- `service/` — `api` (the JAX-RS routes and the `ExceptionMapper`), `bus`, `githost` and
  `daemonhost` (the ci-daemon control socket, the launch registry, the container
  launcher, the live relay and `CiDaemonStepRunner` — the sole implementation of the step seam). It
  read "`api` only" until the daemon control plane landed; the transport lives beside the API because
  it needs a web stack, which is the same line that put `api` here rather than in `ci/`, and it is
  where qits-workspaces keeps its own `daemonhost` for the same reason. `ci/` keeps the
  `CiStepRunner` seam and the orchestrator and gains no web dependency — the step runner is in
  `service/` because it *is* the transport. Two more packages of the same kind: `bus`, both ends of
  the event bus (below); and `githost`, the two clients for the git
  host — the pipeline-config reads and the repository listing (both below). Every one of them is an *adapter* for a seam that lives in
  `ci/control`; that is what the package split says — and every `java.net.http` client living here
  rather than in `ci/` is that same rule applied.

  There was a third, `notify`, holding the deploy announcement — one POST per green run to
  qits-platform-deployments' intake, plus the qits-idp credential it carried. It is **gone**, and so
  is its `PdNotifier` port in `ci/control`: the deployer subscribes to `BuildSuccessful` durably now,
  so what used to be an outbound HTTP call of ci's is an ordinary consumption of the deployer's. The
  intake it posted to still exists as the manual/recovery door; nothing here calls it, and no key
  here configures it. `quarkus-oidc-client` left the pom with that package and has since come back
  for a different hop — the token this service presents to qits-containers, produced in
  `containers/`. See "Authentication".
- `service/…/idp/` — the qits-idp commissioning adapter: the client, the run-scoped memory of what
  it minted, and the reconciliation that reaps what no run owns. Another *adapter*, and another
  hand-rolled `java.net.http` client for the reason the whole `githost` package is one. See "The
  credential is commissioned per run".
- `service/…/containers/` — the orchestrator client's producer and its native-image registration,
  and nothing else. It is an *adapter* like `githost` and `bus` are: the seam is
  `CiStepRunner`/`daemonhost`, and what lives here is only the two things a deployable owes a plain
  jar — a bean, and a `@RegisterForReflection` for the wire records the jar's own `ObjectMapper`
  hides from the build step.
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
on — **no code path here runs repo-controlled code as a host process or through `docker exec`**.
There is no docker vocabulary left at all: container lifecycle is four HTTP calls to
qits-containers, which owns the daemon. `CiDaemonLauncher.BOOTSTRAP` is a `static final String` with
zero interpolation, and it now travels as its own JSON list element (`entrypoint` `["/bin/sh"]`,
`args` `["-c", BOOTSTRAP]`), so that property holds by construction rather than by inspection of an
argv.

**The bootstrap is also the only way to hand a step a FILE, and the registry push credential is the
one that uses it.** The wire has no file field — a spec carries images, environment, mounts and
lifetimes — and qits-ci shares no volume with a step container, so a small file can only be a value
the container writes for itself. `BOOTSTRAP`'s last block writes `$DOCKER_CONFIG/config.json` from
`$QITS_CI_REGISTRY_AUTH_CONFIG` when both are set, which keeps zero interpolation intact (the
credential is a variable the shell reads, never a word in the text) and puts the file at
`CiDaemonLauncher.REGISTRY_AUTH_DIR` under `/tmp` — **outside `/workspace`**, so it is in neither
the clone nor any `docker build` context a step runs from it. The two variables exist only when the
run has **commissioned** a credential (below) **and** the step declared `docker: true`; anything
else sends the environment that always shipped. Note `CiDaemonLauncherTest`
asserts `BOOTSTRAP` contains no `docker` — the variable is upper case and a program would not be, so
that assertion still means what it meant.

### The credential is commissioned per run

**`qits.ci.registry-auth.client-id`/`…client-secret` are gone.** They were one static pair, shared
by every run of every repository, readable by every publishing step's repo-authored script, and
alive for as long as the deployment was. qits-idp grew a commissioning API and `service/…/idp/` is
the adapter for it:

- **`IdpCommissioner`** — hand-rolled `java.net.http`, like every other client here. `POST
  <quarkus.oidc-client.auth-server-url>/api/clients` with HTTP Basic of **this service's own** oidc
  client and `{"contextKind":"ci-run","contextId":"<runId>"}`; `DELETE …/{clientId}` gives one back
  (404 is "already gone", which is what was asked for); `GET …/clients` lists this owner's live ones.
  **The address is derived, never configured** — a second key would be a second thing to keep in step
  with the first, and two idps would mean minting against one and presenting tokens signed by the
  other.
- **`RunCommissions`** — the run-scoped memory, one entry per run, populated **lazily at the first
  `docker: true` step** and reused by every later one. A pipeline of plain steps asks qits-idp for
  nothing; a run is one credential rather than one per step, which is one thing to leak instead of N.
  Not a row: a commission is worth exactly one run, and a run does not survive this process.
- **`CommissionReconciler`** — the durable half. On boot (after both existing boot observers, on its
  own `ci-commission-reconcile` thread — `DaemonReleaseListener`'s healthcheck lesson) and hourly, it
  lists and deletes every `ci-run` row whose `contextId` is not a `QUEUED`/`RUNNING` run and which
  this process is not holding right now. **A listing it could not read reaps nothing**: `live()`
  answers an empty `Optional` rather than an empty list precisely so the two cannot be confused.

Three decisions worth keeping in front of you:

- **A commission that cannot be made fails the STEP.** `CiDaemonLauncher.launch` catches
  `CommissionFailedException` and records `LAUNCH_FAILED` with a message naming the call. Launching
  credential-less would turn an idp blip into a push 401 minutes later, inside somebody's build, with
  nothing in the record naming the cause. The retry window is `qits.ci.commission.patience` and its
  classification is `holdThrough`'s — 401, a 5xx and nothing answering are about the moment; a 403
  (a commissioned client may not commission) and a 400 are about the request and stand at once.
- **The fallback arm is byte-identical to the old unset-keys behaviour.** With
  `quarkus.oidc-client.client-enabled` off there is nothing to commission with, so nothing is
  commissioned and nothing is injected — the arm every test in this repo is on.
- **The secret reaches the container in exactly two forms**: base64 inside the docker document, and
  raw as `$QITS_COMMISSIONED_CLIENT_SECRET` beside `$QITS_COMMISSIONED_CLIENT_ID`, which is what a
  BuildKit secret mount (`--secret id=…,env=QITS_COMMISSIONED_CLIENT_SECRET`) consumes without
  writing a layer. `RunCommissioningTest` asserts that list is exactly one environment entry long and
  that nothing else sent — argv, entrypoint, labels, the container name, the bootstrap — contains it.

**The document names every host in `qits.ci.docker-auth-hosts`, not just the push registry.** The
docker client picks a login by registry hostname, so one entry is one host's worth of auth — which
was enough while a step pulled and pushed against the same address, and stopped being enough when a
step image started arriving `FROM mirror.dev.localhost:8080/…`: a document naming only the registry
leaves the *pull* unauthenticated and the build dies on a 401 no pipeline mentions. The default is
exactly `qits.artifacts.registry-host`, so an unwidened deployment sends the document it always sent;
behind the edge it is both vhosts, and every entry carries the same commissioned pair because it is
one identity at one idp whatever hostname fronts it.

**`DOCKER_BUILDKIT=1` and `BUILDX_NO_DEFAULT_ATTESTATIONS=1` ride along on the same docker-only
scope.** Every step image ships buildx as of qits-oci 2026.814.110556, so a legacy build here is a
*silent fallback* rather than an image with no choice — and a silent fallback is what quietly drops a
`--secret` mount. The first flag turns that into a loud error; the second keeps a push a single
manifest, because buildx attaches provenance and SBOM attestations by default and the platform
registry expects one manifest per tag. Neither is a credential, so both reach a docker step on a
deployment that commissions nothing.

Four things bite:

- **`@WebSocket(path = "/ci/daemon")` is a literal that does not follow `quarkus.rest.path`**, so it
  carries the `/ci` segment itself — and no machine guard reaches it, which is correct rather than an
  oversight: the callers are step containers holding no qits-idp token, and the authentication is the
  per-container secret. Nothing has to be excluded for that to hold. `MachineAuth` guards only where
  a handler calls it, and this endpoint calls it nowhere.
- **The path is a cross-repo contract.** `qits.ci.container-daemon-url` (default
  `ws://qits-ci:8080/ci/daemon`) is injected as `$QITS_CI_DAEMON_URL` and dialled verbatim. Move one,
  move both. It is not a gateway route and must not become one: one process per container with a
  lifetime of one step has no stable address worth configuring.
- **No untimed wait may enter this package.** A run worker parks here instead of on
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
- **This package holds no docker socket and spawns no process.** Every container it needs is
  qits-containers': `CiDaemonLauncher` builds a workload spec and sends it, and the four calls it
  makes — `ensure`, a delete that brings the log back, a plain delete, and a scoped destroy-all —
  are the whole of what `run`, `logs`, `rm`, `ps` and `network inspect`/`create` became. The client
  is a plain jar with a producer in `service/…/containers/`; **it never throws**, and its four
  answers are what every decision here is a `switch` over. A refusal and an unreachable service mean
  opposite things — one is evidence about the request, the other about nothing at all — and a caller
  that collapsed them would read "nothing was learned" as "the container was refused" and start a
  second workload. Do not add a fifth outcome by catching something.

  **`ensure` is ONE attempt per answer ABOUT THE REQUEST, and a 2xx is not automatically a started
  container.** The old rule read "the create was never retried and must not become retried", and the
  half of it that is still true is the half about answers: `SPEC_CONFLICT`, `IMAGE_MISSING`, a 400 on
  a value are one attempt and one recorded `LAUNCH_FAILED`, because this call is on the run worker,
  an `ensure` may already be pulling an image behind a long deadline, and no window makes an
  unpublished image appear. And an `ensure` whose container did not start is a *true answer* —
  a 200 whose envelope says `MISSING`, carrying what docker said — so `launch` reads the observed
  state and records that as `LAUNCH_FAILED`. Reading it as started costs the run its register
  deadline and then records `NEVER_STARTED` about a container that never existed.

  **What the rule never covered is the two answers that are about the moment, and 2026-08-12
  measured the cost.** The deploy train replaced qits-platform-idp and the next three push builds
  died at step launch with `orchestrator refused: refused 401` while every later one passed: this
  service's token, or the orchestrator's copy of the signing keys, belonged to the idp that had just
  been replaced. So a **401, a 403 and an unanswered call** are now held through for
  `qits.ci.containers.launch-patience` (PT90S, ~5s between attempts), and each attempt asks the
  `TokenSource` again — which is the only way a post-cutover token gets picked up. **Retrying is safe
  here for a reason the old `docker run` never had**: `ensure` is a PUT per `(owner, workload, ref)`
  and the ref is the step container's own name, so every attempt addresses the same place and a
  container an unanswered attempt created is *adopted*, not duplicated. The window sits **beside**
  the launch deadline rather than inside it — `launchTimeout()` stays one attempt's deadline, mostly
  an image pull — so the worst case is the patience plus one of those (90s + 60s), far inside the
  fifteen minutes of slop under the registry's `maxAge`, which is what keeps that GC a backstop
  rather than a second timeout. `CiDaemonLauncher.holdThrough` is the one place the classification
  lives; the teardown paths (`destroyWithLogs`, `reap`) share it, since a DELETE is idempotent and
  the same blip reaches them. `destroyAllOwned` needs none — it already retries every non-success
  until its own patience runs out.

- **The boot reap is scoped to this instance's OWNER, and "one qits-ci per docker daemon" is gone
  with the sweep that caused it.** `CiDaemonLauncher.destroyAllOwned` deletes this owner's own
  `ci-step` places created before an instant. It used to be `reapOrphans`, a host-wide
  `docker ps --filter label=qits.ci.run`, which removed *every* labelled container on the daemon —
  including one another qits-ci was running a step in — because after a crash nothing was left that
  could say whose container was whose. The orchestrator's registry is exactly that missing record:
  it names places by owner and no owner can see another's. What is left of the constraint is one
  config key, `qits.ci.containers.owner`, which **must equal the machine token's `sub`** (the
  service's `OwnerGuard` compares them once the gate is on) and therefore defaults to reading
  `quarkus.oidc-client.client-id`. Two instances must not share it. That coupling is argued in ONE
  place, the key's own comment in the `ci` jar's `microprofile-config.properties`.

  **The two boot observers are still ordered, and the order is still reap-then-sweep.** They observe
  one `StartupEvent` and CDI orders two observers of one event only if they ask, so both carry
  `@Priority` — `CiDaemonLauncher.BOOT_REAP_PRIORITY` then `CiRunService.BOOT_SWEEP_PRIORITY`.
  The narrowed scope did not remove the reason: `sweepInterrupted` hands work back to the run
  worker, which asks for step containers at once, and a container this boot just asked for is *also*
  one of this owner's — so a reap running second could still remove it. The second net is
  `createdBefore`, stamped **once at the observer's entry** and reused across every retry, so a
  place created afterwards is outside the set by construction. Order first, instant second; neither
  makes the other unnecessary, and neither annotation moves alone. `BootReconciliationOrderTest`
  asserts the pair and that ArC really honours it.

  **An orchestrator that is not up yet delays the reap and never fails the boot.**
  `qits.ci.containers.boot-reap-patience` (PT60S) is how long it keeps asking; past it, one WARN and
  boot proceeds, exactly the stance the old sweep took toward a docker that was briefly down and for
  the same reason — a process that refused to start because a *teardown* could not run cannot
  recover the runs it is holding. The orphans are then the registry's own `maxAge` GC's.

- **A teardown that needs the log asks for it ON the delete.** `destroyWithLogs` is one call whose
  far side reads the tail and then removes the container, which is what makes the ordering
  unloseable; the unconditional `reap` in the step runner's `finally` then finds nothing, which is a
  success. The tail is bounded **again on this side** against `qits.ci.output-max-chars`: this is
  the last untrusted boundary before the text becomes a row, and a bound only the sender applies is
  a bound a buggy or hostile sender does not apply.

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
  a `QUEUED` row bought. `CiRunService.cancel` finds the row still queued, writes it `CANCELLED` in its
  own transaction, and the worker's claim then sees a row that is no longer `QUEUED` and drops it —
  no container, no `Cancel` frame, no launch to tear down. The flag is still raised, and that is not
  belt-and-braces for its own sake: cancel runs on the request thread and the claim runs on the run
  worker, so if the worker won the race and turned the row `RUNNING` in between, the flag is what
  stops the run before its first container. Neither thread has to win for the answer to be right.

## Reading a repository's pipeline config

`service/…/githost/HttpGitConfigSource` is the only implementation of `CiConfigSource`, and it is
two `GET`s against the git host:

    <qits.ci.git-host-url>/git/<repoId>/blob/<rev>/<path>    the bytes
    <qits.ci.git-host-url>/git/<repoId>/tree/<rev>[/<path>]  {"entries":[{"name","type"}]}

Both answer the commit they resolved in a `Git-Commit-Sha` header, and `<rev>` is a sha **or** a ref
name. Four things follow, and each of them replaced something:

- **The push path reads at the pushed sha.** No branch, no mirror, no race with a second push. What
  went with it is the whole contended-fetch machinery — a bare mirror per repository under a data
  dir, `git init --bare`, a fetch into a ci-private ref two workers could lose a CAS on, the bounded
  retry, `ConfigLookup.CONTENDED` and `CiRunService`'s requeue. All of it existed because the wire
  protocol has no blob-at-path verb, so reading one file meant cloning first. Do not reintroduce any
  of it: the host serves any commit it holds, reachable from a ref or not.
- **A 404 on the blob still means "this commit declares no pipeline"**, which is the opt-in case and
  discards the row. It is told apart from a commit the repository does not hold by one more read —
  the tree at that same sha — because those two mean opposite things to the record. So `GONE`
  narrowed on purpose: it is *held*, not *reachable*, and a commit the branch has moved past now
  builds instead of being discarded.
- **The event path lists `.config/qits/` at the branch, takes the head off the header, and reads each
  file at that sha.** The listing and the reads are one commit even if a push lands in between; a run
  must never be recorded against one commit with a trigger file from another. A 404 on the directory
  costs one more read (the root tree) to tell "declares nothing" from "could not ask".
- **`ci/` stays free of `java.net.http`.** The port is `CiConfigSource` in `ci/control`, the client
  is in `service/`, exactly as `GitHostRepoListing` and `DaemonReleaseLog` are. That split is also why the
  logging differs by path: the push path WARNs (a repository and a branch existed a moment ago),
  the trigger listing stays at DEBUG (it asks every known repository on every frame, and a deleted
  one is simply not a candidate — a warning per green build forever is how a log stops being read).

The host's side of the contract, including the 8 MiB blob cap and why a slashy branch is written
`feature%2Fx`, is qits-artifacts' `README.md` under "Reading one file without cloning". **qits-ci
spawns no `git`**, and the image no longer carries one.

**Both reads carry `IdpGitHostBearer`'s token, and a missing one costs the HEADER rather than the
call.** The bearer is its own named oidc client (`quarkus.oidc-client.githost`) because qits-githost
validates a different audience from qits-containers'. When it has nothing to give — the client is
disabled, or the idp did not answer — the request goes out bare and the git host refuses it, which
is a 401 this class reports like any other status. It used to throw instead, and that was worse in
both directions: with the client shipped `false` every config read of every run failed before a
socket was opened, and the refusal it stood in for is one the host makes anyway. Same rule, and the
same reasoning, as qits-containers' client.

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
event-triggered build. A `RUNNING` push row is still marked `FAILED`; arbitrary push work may not be
safe to repeat. A `RUNNING` event row is reset and restarted from its snapshot. Event-trigger scripts
are therefore an at-least-once boundary and must be idempotent. Nothing here adds durability beyond
the row.

**The row is still the recovery, and durable consumption did not change that.** The reason used to be
that the bus is at-most-once and could not redeliver; it can now (see "The event bus"), and the row
is still what recovers a `RUNNING` event run — because the event has already been *claimed* by the
time a run is running, so the sweep will not offer it again. The two mechanisms cover different
windows and are stacked, not alternatives: the claim covers the arrival, the row covers the
execution.

An event run stores the original timestamp, canonical payload and exact trigger-file content on its
row. Recovery reparses that immutable snapshot: it neither reads a moved branch nor asks the event
log for anything.

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
not `in (SUCCESS, FAILED, CANCELLED, CONFIG_ERROR)`, which reads the same today and rots silently: a new
value added to `ck_ci_run_status` would be finished in fact and invisible to both lists, so a run
would leave one and never arrive in the other. Written as a complement they partition the table by
construction. `/finished` carries `?limit=` where `/active` does not, and the asymmetry is the whole
difference between them: what is active is bounded by accepted work and the configured worker pool,
what is finished grows with the instance's uptime. Absent means **5**, not unbounded — the opposite
of the repository listing's default, because there is no repository here to make "all of them" a
bounded question — and an ask above **100** is clamped rather than refused, since this is the one
listing that is both unscoped and otherwise unbounded.

**The machine guard is a call in the handler, not a filter over a path.** `CiEventController` opens
its one write with `machineAuth.requireProject(QitsClaims.ANY)`. Nothing matches a path, so renaming
a `@Path` moves the guard with the route and can no longer detach it. The fail-open shape that
replaced the old one is narrower and still real: a **new** write method that simply omits the call
ships unguarded, and nothing says so.
`MachineGuardTest` is what stands between that and shipping. It runs with
`qits.auth.machine.required=true` and POSTs each write's absolute address, demanding 401 with no
token, 403 for a token whose `aud` or `project` claim does not cover the target, and **the
endpoint's own answer** for one that does — 503 at the manual trigger, which
evaluates before it answers and has no git host to ask in that profile. Either way the case rules
out 401 and 403, which is the whole of what a guard test can say.
**The push intake used to be the second guarded write and is not a write at all any more**: a push
arrives as `SCMPublishCommit` off the bus, where a bearer would mean nothing, so the cases that
asked "may this token push to this repository" have no endpoint left.
Add a write endpoint, add its case there, and keep every address in that test absolute: a moved
prefix then shows up as a 404 rather than as a pass.

**The reads are not open, and `@RolesAllowed` shuts before the guard call is reached.** Every
controller carries a class-level role: `qits:admin` on `CiRunController` and `CiRepositoryController`,
which is what qits-spa-ci's session holds, and `qits:system` on `CiEventController`,
`CiDaemonController` and `CiDaemonSocket`, which is what a machine peer holds. So three doors shut in
order, and `MachineGuardTest` pins which: no token is 401, a token granted no roles is 403 at
`@RolesAllowed`, a wrong audience or an uncovered project is `MachineAuth`'s own 403. **A machine
token carries its roles in the `groups` claim** — qits-platform-idp copies them there from
`qits.idp.client.<id>.roles` and quarkus-oidc reads that claim as roles with no configuration at
all — so a fixture that mints a token without `groups` authenticates perfectly and is then refused
403, which is a stale fixture rather than a regression. A method-level role list **replaces** the
class-level one rather than adding to it; a route both a person and a machine read must name both.

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
`QitsEventListener`, `QitsRawEventListener`, `QitsDurableEventListener`, `CausationScope`). It used
to be a directory here, a
library waiting to move out; it has moved, and qits-ci is now an ordinary consumer that happens to
build it in the same reactor. The design is the superproject's `eventsourcing-plan.md`,
`event-causation-plan.md` and `event-delivery-guarantees-plan.md`.

**Its rules are in its own repository and are not restated here.** Read `eventstream/AGENTS.md`
before changing anything about how this service publishes or listens; the seven that bite are the
canonical form as a wire contract, `eventId` fixed at construction, the HTTP/1.1 pin, an outbox that
is empty in a healthy process, causation stamped in the envelope by the bus alone, the three
consuming seams with their subscribe-frame union, and the durable one's exactly-once *effect* with
its "a throw leaves the event owed" failure policy. A second copy of any of that in this file is a
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

`service/…/bus/` is the whole of qits-ci's wiring: two announcers publishing, and four listeners
consuming. The subscriber dials itself on `StartupEvent` because listener beans exist. Registering a
listener really is "add a bean" — no channel name, no annotation — and no `@Unremovable` is needed,
because `EventDispatcher`'s `Instance<QitsDurableEventListener>` is what ArC counts as a use.
`EventstreamDarknessTest` asserts that rather than trusting it, since a removed listener subscribes
to nothing, is swept for nothing, and says nothing about it.

**All four consume DURABLY, and none of them is a typed or raw listener any more.** The library's
other two seams are live-only and at-most-once — a frame broadcast while this process is
disconnected, restarting or mid-cutover is gone — and the 2026-08-10 rebootstrap campaign measured
what that costs a platform whose release train rides the bus. `QitsDurableEventListener` is the
answer: one funnel claims the event in `consumed_event` and calls the handler in one transaction, and
a watermark is paged forward from qits-events' log at startup and on a schedule, so a disconnect is a
delay rather than a hole. `event-delivery-guarantees-plan.md` in the superproject is the design and
`eventstream/AGENTS.md` is the contract; what follows is only what is qits-ci's to get right.

**Each listener's `consumerId()` is STORAGE, not a label**, and the five shipped are literals in
`EventstreamDarknessTest` for that reason:

| bean | `consumerId()` | `signatures()` | `selects` |
|---|---|---|---|
| `ScmPublishCommitListener` | `ci-push-runs` | `SCMPublishCommit` | default |
| `CiEventTriggerListener` | `ci-event-triggers` | `["*"]` | default (see below) |
| `BuildSuccessfulListener` | `ci-release-train` | `BuildSuccessful` | default |
| `DaemonReleaseListener` | `ci-daemon-adopt` | `SoftwareRelease` | the daemon's own releases |
| `ScmReleaseListener` | `ci-release-facts` | `SCMRelease` | default |

**`ScmPublishCommitListener` is the push intake**, and it replaced an HTTP endpoint rather than
joining one. `POST /ci/api/events/post-receive` is gone: qits-githost publishes `SCMPublishCommit`
(one per successfully updated branch ref) and this bean calls the same `CiRunService.onPostReceive`
the endpoint called, so validation, the `QUEUED` row and the supersede of an older queued push for
the same `(repoId, branch)` are unchanged. Three things about it are worth knowing before touching
it:

- **`suppressCi` is honoured here and nowhere else.** `-o qits.no-ci` used to be the git host's
  decision — it skipped the POST — and is a fact on the event now, so a run engine skips the build
  while another consumer may ignore the flag entirely. Skipping records **no row**: nothing to
  supersede, nothing to cancel.
- **A push run carries the frame's id in `trigger_event_id`, and that is what closes the causation
  chain.** `announceRun` reads it back off the row and `CausingEvent.parentOf` makes it the parent of
  the run's `BuildSuccessful`, so release → push → commit event → CI run → deploy is **one** chain.
  It used to be two halves with a root event in the middle, because the intake was an HTTP POST with
  no event behind it to name. Carried as data on the row rather than in `CausationScope`, for the
  reason the trigger engine already had: that scope is a `ThreadLocal` and the publish happens on
  `ci-run-worker`, possibly after a restart.

  **It puts push runs inside `unique (trigger_event_id, repo_id, config_path)`, which is a second
  guarantee rather than a cost.** A push's config path is the constant `ci-post-receive.yml`, so the
  event id is the whole of what tells one push row from the next — one announced push, one run. The
  claim ledger settles a redelivery before the handler is called at all; the constraint is the net
  underneath, on ci's own datasource where the claim is not. A duplicate is **settled**:
  `acceptPostReceive` catches the violation, returns null, nothing is enqueued and nothing is
  superseded (the flush threw before the supersede loop, which is right — a push already recorded
  must not cancel the queue a second time). Throwing would leave a push owed forever over a run that
  already exists. `NULL` there now means "nothing announced this", which is `CiRunService.execute`,
  the synchronous test entry, and nothing in production.
- **Its failure policy is the usual pair**: a payload that will not bind and an identifier
  `CiIdentifiers` refuses are poison (WARN, settled — what was a 400 when there was a caller to
  answer), and anything `onPostReceive` throws is left to throw.

The dependency this adds is `eu.wohlben.qits:qits-githost-events`, the vocabulary jar — four records
and the bus, no client and no address. It is the only compile-time dependency this repo has on
another context, and "Adding a dependency on another context" below is about *clients*, not about a
published event's shape.

Change one and you mint a brand-new consumer: the old claims are orphaned and the new id initializes
at the head of the log, silently skipping everything in between. Reuse one and a listener inherits
another's watermark, believing it has handled events it has never been offered. `ci-release-train` in
particular says what the *consumption* is, not what the class is called — and note it is not where
release-train membership is decided. That is the trigger engine's, below.

**Every handler failure is a decision between two, and it has to be made deliberately.** A throw
rolls the claim back and the event stays owed — offered again forever, with this listener's watermark
stuck behind it, because the seam has no dead letter and says so. So: **retryable** (the store is
down, the queue is full — a later attempt could succeed) is left to throw; **poisonous** (a payload
that will not parse, an event with no name, a release with no `occurredAt` — the same bytes will fail
identically every time) is a WARN and a return. Each of the three javadocs names its own cases;
`DurableBusConsumptionTest` asserts them, `HANDLED` versus `FAILED`, through the funnel itself.

**The suite must not be swept behind.** `service/src/test/resources/application.properties` sets
`qits.eventstream.catchup-at-startup=false` and stretches `qits.eventstream.catchup-interval`,
because a tick landing mid-test would adopt a daemon release or enqueue a trigger evaluation nothing
asked for — and `StubEventsServer` scripts a canned list and ignores the cursor, so it would do it
repeatedly. The scheduler stays *on*: the outbox sweeper is scheduled too and has no business being
disabled. A test that wants a sweep calls `CatchupSweeper.catchUp()` itself, which is what the
eventstream suite does.

**The publish hook hangs off a seam, and it is now the ONLY thing a green run announces.**
`RunAnnouncer` (in `ci/control`, implemented in `service/`) is what keeps the `ci` module free of the
bus. It used to be the second of two — `PdNotifier` was beside it, a direct POST asking
qits-platform-deployments to deploy, and the two were separate ports because a request to one named
service and a statement to the platform are different things. The deployer consumes `BuildSuccessful`
durably now, so the request became a consumption and the port retired; the statement is what is left,
and the shape of it did not change. `finishedAt` is on the signature because an event carries when it
happened,
and it comes back out of `finishRun` rather than off the `CiRun` instance: that method mutates a
freshly loaded entity in its own transaction, so the caller's copy never sees the value. **A null
`occurredAt` is a 400 from qits-events on every green build**, which is why the seam test asserts
the timestamp rather than only the coordinates.

**There are two publishing seams and they are separate because they say different things.**
`ReleaseAnnouncer` (`ci/control`, implemented by
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
  warn about nothing; a declaration whose trigger carries no version is a WARN and no event, since
  a blank version would publish a package reference nothing can resolve. **Two events feed it and
  they spell it differently**: `version` on an `SCMRelease`, `tagName` on an `SCMPublishTag`, whose
  value IS the version string because a release stamp is the name of the tag the release push
  created. `CiRunService.releaseVersionOf` is the one place that choice is made, and it had to exist
  before the join below could have anything to join on.

### The release join, and why the port has a gatekeeper now

**A green release pipeline no longer announces on its own.** `CiRunService.announceRelease` hands
what the run published to `ReleaseJoin` (`ci/control`), and that is what calls `ReleaseAnnouncer` —
only once an `SCMRelease` for the same `(repository, version)` has been seen. bootstrap-replay-plan.md's
WP2; the class javadoc carries the argument in full and the short form is:

- A **restore** re-establishes SCM state and produces `SCMPublishTag` alone. A **release** also
  announces novelty, and only qits-workspaces publishes `SCMRelease`. Announcing off the tag made
  every rebootstrap impersonate a release — the train woke against a platform the boot had not
  finished deploying, and the same eight repositories went red every time.
- **The two facts race on a real release, so both halves are rows.** `ci_release_announcement` holds
  what a green run owes (`announced_at` null is "still owed"), `ci_scm_release` holds what was really
  released. Either arrival order works and a restart between them costs nothing.
- **An `SCMRelease`-triggered run takes no lookup at all.** The event that caused it IS the release
  announcement, so the join closes by construction — which is what keeps every recipe that still
  declares `event: SCMRelease` behaving exactly as before, and what keeps the manual trigger door
  working (a hand-supplied event rides no bus and leaves no fact row).
- **A tag-triggered run with no release behind it never announces. No timeout, no fallback.** The
  owed rows stay as the readable account of what published without being released.
- **Published first, marked after**, inside one transaction holding the owed rows locked
  (`lockOwed`). So two drivers of one key cannot both announce, and a crash between the publish and
  the commit leaves the row owed for the boot sweep. At-least-once by choice: losing an announcement
  is the failure the platform forbids, making one twice is the nuisance the other way round.
- **The boot sweep runs on its own thread** (`ci-release-join-sweep`), and that is the
  `DaemonReleaseListener.reconcileFromLog` lesson applied: a startup observer that blocks on the
  network loses the healthcheck race and cd kills the deployment.
- **`ScmReleaseListener` writes in its own transaction, not the claim's.** The claim lives on the
  eventstream datasource and the fact row on ci's, and one JTA transaction does not take both —
  measured, as `Enlisted connection used without active transaction`. `acceptPostReceive` already had
  the same arrangement for the same reason. The fact is written before anything is announced, so the
  direction that can go wrong is a re-offered event finding the row already there, which is a no-op.
- **`SCMRelease`'s name and its three payload fields are strings here**, like the tag event's, and
  their guard is `bus/ScmReleaseContractTest` — the same shape as the tag event's with one
  indirection, because **qits-workspaces publishes no vocabulary jar**. Measured 2026-08-12: the
  platform Maven registry serves `qits-githost-events` and `qits-eventstream` and answers `nothing
  is deployed` for `qits-workspaces-events`, so depending on it would compile from a developer's
  `~/.m2` and fail to resolve in the release pipeline's own step container. What that test holds
  instead is a **transcription** of the record's component list, named against its source file, run
  through the real `CanonicalJson` — so the wire rules stay the library's and only the component
  list is hand-kept. `DurableBusConsumptionTest` drives those same canonical bytes through the real
  listener, so the strings are proved to work rather than merely to be present.
  `ReleaseJoin.RELEASE_EVENT_NAME` is the one place the name is spelled. **A rename in
  qits-workspaces is a change to that transcription in the same campaign**; landing it there and not
  here leaves this suite green and the join dead, which is the one failure the test cannot prevent
  and says so out loud.

The call sits on a run worker and it blocks. That was the trade, and it is bounded
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
  not stop the datasource. Quarkus opens the connection and runs Flyway at boot regardless, which is
  why `service`'s `EmbeddedPgConfigSource` hands out SIX values rather than three: the outbox gets a
  database of its own on the same embedded postgres or the suite does not start.

  **The deployment side of that same sentence cost a rollout, so it is worth stating plainly: adding
  this module to the deployable adds a MANDATORY deployment resource.** It used to be a mandatory
  VARIABLE — `QUARKUS_DATASOURCE_EVENTSTREAM_JDBC_URL`, which had to point at the data volume — and
  the rollout it cost was the shipped `${user.home}` default behind it: right for a host-run process,
  and in a container with no `HOME` the native binary resolved it to `?`, which H2 rejected outright.
  The process died at Flyway before serving anything. That was **a config default no JVM test
  exercises, failing only in the packaged artifact in its real environment**, alongside the
  `AUTO_SERVER=TRUE` that killed the binary and the IPv4 bind.

  What replaced it cannot fail that way, because there is no default left to be wrong:
  `.config/qits/deployments.yml` declares `resources: postgresql:db,
  postgresql:eventstream:qits_ci_eventstream`, qits-platform-deployments creates both roles and
  databases before the container starts and injects `QITS_RESOURCE_DB_*` and
  `QITS_RESOURCE_EVENTSTREAM_*`, and the two jars read those variables in their own shipped
  defaults. **The resource NAMES are load-bearing** — the variable names follow them — so renaming
  either in that file silently stops matching the jar that reads it. An unset variable leaves the
  expression unresolvable and the process refuses to boot, loudly and safely, since the health gate
  keeps the previous container.

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
  with a 201, and trigger a `steps: []` pipeline through `POST /ci/api/events/trigger` — a zero-step
  pipeline reaches SUCCESS with no docker and no daemon, which is the shortest path there is from a
  fresh binary to a published event. (It used to be a POST to the push intake, which no longer
  exists; a push now needs a real `SCMPublishCommit` on the bus.)
  Read the PUT body. Anything about this module that only the binary can be wrong about is one minute
  of native-image away from being known rather than believed.

  **The far end of that failure was mute, and that is fixed too.** `EventDispatcher` logged a frame
  it could not read at DEBUG, so a binary that could not deserialize `EventFrame` would have consumed
  the entire stream in silence for as long as it ran. It is a WARN now, naming the frame's `name` and
  `id` when the text is JSON at all (a second, untyped read — `readTree` needs no reflection, which
  is precisely why it still works when binding does not). An unknown *signature* stays DEBUG: that
  one is ordinary traffic, since a subscription set is a filter rather than a promise.

`quarkus-scheduler` (the outbox sweeper's, and now the catch-up sweeper's) arrives transitively with
the jar and is new to this
deployable; `quarkus-websockets-next` was already here for the ci-daemon control plane, so the
client half costs the image nothing. `quarkus-undertow` stays absent — check it with the
`dependency:tree` line under "The Angular client" after touching this pom.

## The trigger engine

There are **two** trigger types now, and the second one is the reason a frame-shaped consuming seam
is needed at all.
A repository commits `.config/qits/ci-event-<anything>.yml` naming a domain event and a selection
over its payload; a matching event on the bus runs that file's pipeline against the head of `main`.
The design is the superproject's `ci-event-triggers-plan.md`, the format is `README.md`, and what
follows is what biting it feels like.

- **`ci/` stays free of the bus's SEAMS, in both directions.** `service/…/bus/CiEventTriggerListener`
  is the `QitsDurableEventListener` bean; it turns an `EventFrame` into
  `CiEventTriggerService.Arrival`, four plain strings, and hands it over. That is the same seam shape
  `RunAnnouncer` is on the publishing side, pointed the other way, and it is why
  `CiEventTriggerService` — which does the real work — imports no publish/subscribe type. Keep it
  that way; the extraction rule protects the library, and this one protects the domain.

  **The word is SEAMS now, not "the bus", and the narrowing was deliberate (2026-08-10).** The
  eventstream jar also carries the platform's causation *persistence vocabulary* — `CausedRow`,
  `CausationStamp`, `@Uncaused`, three jakarta-persistence-shaped types with no publish, no
  subscribe and no wire in them — and `CiRun` implements it, so the jar sits in `ci/`'s pom now.
  What the rule still forbids is control flow: no listener, no publisher, no `EventFrame`, no
  `QitsEventBus` anywhere in `ci/`. What the dependency costs is honest and paid in the suite: the
  jar's persistence unit boots in this module's tests too, so `testdb/EmbeddedPgConfigSource` feeds
  it `eventstream_ci_domain` and the test properties keep the bus dark — the same consumer contract
  the service module has always honoured.
- **The manual trigger is a second inbound adapter of the same evaluation, on a different thread.**
  `POST /ci/api/events/trigger` (`CiEventController`) builds the same `Arrival` from a JSON body, so
  the engine cannot tell a hand-supplied event from a frame — no branch, no flag, no second code
  path, and nothing web-shaped reaching `ci/`. It is the only operation left on that resource — it
  shared it with the push intake until that became a listener — and it demands `project=*`, because
  an event names no repository. The **id default is load-bearing**: a
  fresh random UUID per call, or the dedupe below silently drops every rerun. A caller that passes
  one is opting into the dedupe, which is what makes a bootstrap script idempotent. Both are in
  `README.md` under "Triggering one by hand", and both are pinned by `CiManualTriggerTest`.

  **What it does NOT share is the queue, and that is a fix rather than an inconsistency.** It calls
  `evaluateNow`, which runs the evaluation on the request thread and answers what it did:
  **200** with the run ids it recorded (empty and nothing skipped = "asked everybody, matched none"),
  or **503** when no candidate repository could be read. It used to call `onEvent` and answer 202
  whatever came back.

  **The reason is a property only the bus has.** A frame that is not evaluated stays *owed*: the
  claim rolls back and the next catch-up sweep offers it again. A caller-supplied event rides no bus,
  is on no log and holds no claim — nothing anywhere will ever offer it a second time — so "handed to
  a queue" and "lost" were the same outcome for it. Two ways that queue swallows an event and neither
  says a word about it: it is bounded, so a full one is a `false` the endpoint used to discard; and
  it is one thread, so a worker slow or stuck inside a git-host read holds everything behind it with
  no log line at any level. On **2026-08-10** a bootstrap's release replay was answered 2xx for an
  event that was never evaluated — no run, nothing logged, thirty minutes. `CiEventTriggerServiceTest`
  stages exactly that state (worker wedged, queue refusing) and asserts a manual evaluation still
  records its run.

  **The price is stated rather than hidden:** "one git-host fan-out at a time" is now a statement
  about *bus* traffic, and a manual call fans out beside the worker. That is the right way round —
  the budget exists to keep a burst of machine events from storming the git host, and a manual
  trigger is one request from one person, already bounded by the HTTP worker pool. The call is also
  as long as the evaluation, so `qits.ci.trigger-deadline-seconds` (60) bounds it; candidates not
  reached come back in `repositoriesSkipped` rather than being dropped silently.

  **A skipped repository is not a repository that said no.** `EventTriggerLookup.UNREACHABLE` mixes
  "the git host is down", "the repository is gone" and "it has no `main`" on purpose, so the endpoint
  reports the count it *read* beside the ids it could not, and refuses (503) only when it read none.
  Every candidate silent is a statement about the git host, never about the event — the same rule the
  candidate list and the run queue state for an unreachable host.
- **`signatures()` is `Set.of(ALL)` permanently, and it is not laziness.** The wire set is derived
  only when the connection is opened and **the subscriber does not dial at all when the union is
  empty**, so a listener that answered `Set.of()` until it had read some config would never open the
  stream it would read config over. `"*"` is the seam's documented idiom for exactly this, and the
  cost is that this deployable's subscribe frame is literally `["*"]` —
  `CiEventTriggerCausationTest` reads it off the stub to keep that from being a belief.
- **`selects` is left at its default, and that is a decision with a price.** The durable seam asks a
  listener to narrow with a *pure, cheap* predicate and stores only what it selects. This engine's
  selection is neither: deciding whether an event matches anything means listing `.config/qits/` in
  every candidate repository and parsing each trigger file, over HTTP, against answers that change
  with every push. Putting that in front of the claim would fan out on the dispatch thread — and a
  `selects` that throws leaves the event *owed*, so one unreachable git host would wedge the
  watermark. So this consumer selects everything, and **every event on the bus leaves it a claim
  row**. Bounded rather than unbounded: the sweeper prunes claims the watermark has passed by more
  than `qits.eventstream.prune-horizon`, so the table is the stream/catch-up overlap window and not a
  second copy of the log.
- **Three threads, and each boundary is deliberate.** `onFrame` runs on the bus's websocket worker
  (or the catch-up sweeper's thread), one frame at a time for *every* consumer, so it only enqueues.
  Evaluation runs on its own
  single-threaded `ci-trigger-worker`: not the dispatch thread because it reads the git host once per
  candidate repository, and **not `ci-run-worker`** either, though that is the obvious reuse — that
  thread is inside a running pipeline for minutes, and an event evaluated when the build ends is
  evaluated against a `main` that has moved. Single-threaded was once a correctness rule (two
  evaluations of one repository raced for one bare cache on disk); with the caches gone it is a
  budget — one fan-out at the git host at a time. The queue is bounded.

  **A full queue is a failure, not a WARN and a shrug**, and that is what `onEvent` returning a
  boolean bought. The engine answers whether it *accepted* the event; a `false` means it was not
  evaluated, which is a statement about this process being busy rather than a verdict about the
  event, so the listener throws and the next catch-up sweep offers it again. **`onEvent` is the bus
  listener's and nothing else's now**: everything that reaches it has a redelivery channel behind
  it, which the manual trigger never had — see that bullet above.

  **State the residual window plainly: the claim commits when the event is ACCEPTED, not when the run
  row exists.** A crash in the gap between the enqueue and the evaluation loses that event. It is a
  narrower guarantee than the seam's "exactly-once effect", and it is the deliberate price of keeping
  the git-host fan-out off the dispatch thread and out of the claiming transaction. Both ends of the
  gap are covered — a full queue fails rather than drops, and an accepted run is a `QUEUED` row that
  survives a restart on its own — so what is exposed is milliseconds, not the disconnect windows this
  migration existed to close.
- **The causation edge crosses those threads as data, not as context.** `CausationScope` is a plain
  `ThreadLocal` and does not follow work — that is its design, not its limitation. So the frame's
  `id` is written to `ci_run.trigger_event_id`, read back off the row at `announceRun`, and passed to
  `publish(event, parent)` as an explicit argument, which outranks the ambient context precisely for
  this case. It survives a restart, which no context could. Note it is the frame's `id` and never its
  `parentId`: the arriving event causes this run, its own parent is the previous hop's business.
- **The dedupe is a database constraint and the `NULL` behaviour is load-bearing.** `unique
  (trigger_event_id, repo_id, config_path)` is the at-most-one-run-per-(event, trigger file)
  guarantee, and it has to be a constraint rather than a check because what it survives is a race and
  a restart. **It covers pushes too**, since a push run is named by the `SCMPublishCommit` that
  announced it — one announced push, one run. What carries a null there is a run nothing announced
  (`CiRunService.execute`, the test entry), and a database treating two nulls as duplicates would
  make the second such row fail to insert — SQL says rows collide only when all corresponding values
  are non-null and equal, and `CiEventTriggerDedupeTest` pins that the database agrees rather than
  trusting it — plain
  `unique`, never postgres' `nulls not distinct`, which is exactly what must not be asked for. The
  constraint
  kills replays, not descendants, which is why it is **no loop guard**: see the footgun in
  `README.md`, and note that nothing here is built that the future DAG feature would have to undo.

  **It fires at accept now**, since the run row is written by `onEventTrigger` before it returns
  rather than by the worker later. Nothing about the semantics moved with it: a redelivery still hits
  the constraint and is still dropped as already-triggered, just on `ci-trigger-worker` instead of
  `ci-run-worker`, and before a queue slot is spent rather than after. The push path reaches the same
  constraint on the bus's dispatch thread, in `acceptPostReceive`, and answers a duplicate the same
  way: null, and an INFO saying the first run stands.

  **The durable claim sits above it and neither replaces the other.** A `consumed_event` row makes
  one event reach the engine at most once, so the constraint fires far less often than it used to —
  but it stays, because it is on ci's *own* datasource and the claim is not, and what it survives is
  a race between two evaluations and a restart mid-evaluation. Two nets, one of which is transactional
  with the run row. Deleting either for tidiness trades a guarantee for a diagram.
- **There is a SECOND collapse on this path and it is not that one.** `CiRunService.supersedeByVersion`
  is the event path's twin of the per-branch push supersede in `acceptPostReceive`, down to the
  columns it writes, and it exists because **`SCMPublishTag` is announced once per tag ref of a
  push**. The publisher is right to emit all of them — a tag is a fact and qits-projects' backup
  consumer needs every one — so a trigger file declaring `event: SCMPublishTag` would get one run per
  tag, four of five building a version nobody asked for. The collapse therefore belongs to the
  consumer that turns a fact into work.

  **Nothing had to be widened for the tag event to be selectable.** `CiEventTriggerListener` says
  `"*"` permanently and the engine matches a trigger file's `event:` against the arriving name as a
  string, so a repository could always name it; what was missing was only the dedupe. `signatures()`
  is not a list anybody adds an event to.

  Four things about it. It runs **inside `acceptEventRun`'s transaction, after the flush**, so a
  superseded row and the row that superseded it commit together. It touches **`QUEUED` rows only**,
  which makes it best-effort by design — a lower tag already running keeps running, because
  cancelling a build to save time it has already spent is the worse trade, and what is guaranteed is
  convergence rather than minimality. The loser **may be the run being accepted**, since a fan-out
  arrives in no order; its row stays as the record that the tag was announced and the worker's claim
  drops it, which is the path a cancelled queued run already takes. And an **unreadable tag
  supersedes nothing** while an **equal one supersedes** — a failure to compare is not a lower
  version, and a tag that moved is the same case a second push to a branch is.

  **`VersionSort` is hand-rolled and `TAG_EVENT_NAME`/`TAG_NAME_FIELD` are strings**, both for rules
  already on this page: no dependency the native-image builder has to be told about, and `ci/`
  names no other context's types. The strings are the one thing a compiler cannot check, so
  `bus/ScmPublishTagContractTest` resolves them against the real `SCMPublishTag` in the module that
  has the jar — the same guard shape `EventWireReflectionTest` puts over the mix-in's class name.
  Rename the event or the field in qits-githost and the suite goes red, rather than the supersede
  quietly ceasing to fire.
- **The trigger file parser is strict where `ci-post-receive.yml` is lenient**, and the asymmetry is
  the point rather than an inconsistency. In a pipeline an unread key costs a feature; in a
  *selection* it costs correctness, because an absent `when:` means **unconditional** — so a mistyped
  `wehn:` would silently widen the trigger to every event of that name. Unknown top-level keys and
  duplicate keys are therefore errors in a trigger file and are not in a pipeline. The `steps:`
  schema is shared verbatim (`CiConfigSchema`), because a step must not mean two things.
- **`artifacts:` is the one key the trigger file adds rather than subtracts**, and it is what makes a
  file a *release pipeline*: a non-empty list of `{type: npm|maven|docker|daemon, name: …}`, strict in every
  direction (empty list, unknown type, blank name, extra key, wrong shape — all parse errors naming
  the file). It is a parse error in `ci-post-receive.yml` for its own reason rather than by symmetry
  with `branches:`: what a declaration announces is the *triggering* event's version, and a push
  carries none, so the key could only ever be inert there. The declaration is a **claim**, never an
  observation — qits-ci cannot see what a step pushed — and it is declared rather than emitted
  because a declaration is statically readable, which is the whole of what the parked cycle-detection
  work needs. The daemon's return channel could not have carried an emission anyway: it is
  `StepChunk` and `StepFinished`, and a stdout sentinel is forbidden by design.
- **The candidate list was the feature's one acknowledged compromise, and it is the worked example of
  a seam paying for itself.** It read: qits-artifacts exposes no listing, so `KnownCiRepos` answers
  with what qits-ci already knows — recorded runs' repo ids — and a
  repository that has never pushed cannot event-trigger until it does. That cost was real: it blocked
  bootstrapping a platform seeded straight onto the git host, which is exactly what `POST
  /ci/api/events/trigger` exists for. The git host has since grown the listing, the swap was the one
  class the javadoc promised, and nothing in the engine moved.

  **What ships now is a union, and union is the design rather than a step towards replacement.**
  `ListedAndKnownCiRepos` is the bean the engine gets: `GET <qits.ci.git-host-url>/git` →
  `{"repositories": […]}` through the `GitHostRepoListing` port, **added to** `KnownCiRepos`' answer.
  The listing is one HTTP call away, so an unreachable host, a non-200, a body that is not JSON and a
  body with no `repositories` array are each one WARN naming the url and an empty contribution — the
  answer is then the known set alone, which is precisely what shipped before. **A read failure must
  never shrink the candidate set**, the same rule the run queue states for an unreachable git host one
  section up. The two halves also age in opposite directions: the listing is what the host has now,
  the known set still covers a repository the host has stopped listing but ci holds a row for.

  Four things about the HTTP half, which is `service/…/githost/HttpGitHostRepoListing` and is in
  `service/` because **`ci/` stays free of `java.net.http`** — the rule `DaemonReleaseLog` states and
  `CiConfigSource` set:

  - **The url is derived, never configured.** It is the git host's own base plus the same `/git`
    segment `HttpGitConfigSource` reads content under, so the listing and the config read move
    together.
  - **The timeouts are short because of which thread this is on.** 2s connect, 3s for the whole
    exchange. It runs on the single-threaded `ci-trigger-worker` in front of every evaluation, so an
    untimed call would stall *every* arriving event; and the evaluation it precedes reads the host
    per candidate, so the listing must never be the slow part. Past the deadline the known set is a
    correct answer.
  - **The cache is five seconds and deliberately trivial.** One evaluation already costs a read per
    candidate, so this is not an optimisation — it is so a burst on the bus is one listing read rather
    than one per frame. Only a *successful* read is cached, because caching a failure would keep a git
    host that came back up invisible for the window.
  - **A non-HTTP git host is a DEBUG, not a WARN.** A value that is not an HTTP url serves no
    listing and never could; warning on it would be a line per event forever, which is the same
    argument `HttpGitConfigSource` makes for keeping the trigger-listing path's failures at DEBUG —
    that path asks every known repository on every frame, so one deleted repository would otherwise
    cost a warning per green build forever. Ids off the listing are filtered through `CiIdentifiers`
    before they reach a url.

  `KnownCiRepos` stays `@DefaultBean` and `ListedAndKnownCiRepos` is an ordinary bean, which is the
  whole of the CDI arrangement: the ordinary bean wins the engine's injection point while the default
  one stays injectable by its own type, and a `@Mock` alternative outranks both — so
  `FakeCandidateRepos` still replaces the seam whole and still exercises the swap it was written for.
- **Nothing here needed native-image registration**, and `EventWireReflection`'s javadoc says why in
  full: SnakeYAML's `SafeConstructor` produces plain collections, the parser builds its records by
  hand, and the payload is `readTree`'d into a `JsonNode` and walked. No binding, no reflection, no
  fifth member of the family this file names. Check that reasoning again if the engine ever gains a
  Jackson `readValue`.

## Adding a dependency on another context

Don't. Things arrive as an event off the bus, or as a URL in config, or not at all. There is
no `RepositoryLookup`-style port here and there should be no need for one: a run knows a repo id, a
branch name and a sha, and everything else it wants it fetches from the git host itself.

**`qits-githost-events` is the first exception, and it is the shape of one rather than a hole in the
rule.** It is a *vocabulary*: four records and the bus, no client, no address, nothing to call. A
published event's shape is exactly the kind of contract a jar may carry, and the alternative —
reading another service's payload by string key — is worse in every direction.

**`qits-containers-client` is the second, and it IS a client — so the rule needs its real boundary
said out loud.** What is forbidden is a dependency on another **bounded context**: a
`RepositoryLookup`, an SDK for qits-projects, anything that turns another domain's availability into
this one's. What is allowed is **platform infrastructure**, and the test is the one the eventstream
jar already passes: is this the platform's single answer to a capability every module needs, or is
it one context's model? qits-events is where the platform's events live; qits-containers is where
its containers live; qits-idp is where its identities live. A client for one of those is the same
kind of thing as a datasource driver — this service could no more start a container without it than
it could open a connection without JDBC.

Three properties travel with that, and a jar missing them is not infrastructure:

- **No domain model crosses.** The wire is images, environment and lifetimes — nothing about
  repositories, runs or pipelines. qits-containers cannot name a `CiRun` and does not want to.
- **The availability cost is honest and bounded.** Every call is synchronous with a deadline and
  <em>cannot throw</em>; an unreachable orchestrator costs a step its launch, recorded as
  `LAUNCH_FAILED`, and never the process.
- **The jar brings no framework.** No arc, no OIDC extension, no container: `ContainersClient` is a
  plain class this service makes a bean of in a producer of its own.

A dependency that fails any of those three is a client on another context, whatever it is called.

**Pin both at RELEASED calver, never at a snapshot, and 2026-08-12 is why.** Each carried
`1.0.0-SNAPSHOT` behind a comment saying it must not ship as one — correct, and inert, because the
bootstrap seed-published both into the registry and they resolved there for months. A salvage
re-seeded the artifacts store without them and **nothing went red**, because every qits-ci build
afterwards reused docker's cached maven layer: ten CACHED layers, including the one that resolves
dependencies. The first source change since invalidated that layer, resolved for real, and the
release run died on both jars at once. The lesson generalises past these two: **a green build that
did not resolve anything is not evidence that it could**, so when a pin changes — or when the
registry is re-seeded — purge the artifact from `~/.m2` and build, which is the only local way to
ask the registry the question the step container will ask.

Never add a JPA relation to another context's entity. `ci_run.repo_id` is a plain `String` column
in ci's **own** physical database; a foreign key cannot span it.

## Untrusted input

Four things reaching this code are attacker-controlled and must stay that way in your head:

- **The `SCMPublishCommit` payload.** It says what somebody pushed, so the repo id, the branch and
  the sha on it are as attacker-shaped as the intake POST they replaced — a durable event with a
  claim row behind it establishes *delivery*, never content.
  `CiIdentifiers.require{RepoId,Branch,Sha}` validates all three *before* they reach a
  filesystem path or an argv. Never widen those, never bypass them, never interpolate an identifier
  into a shell string. What changed with the transport is only the answer to a refusal: a 400 to a
  caller became a WARN and a settled event, because there is no caller.
- **The step's `image`.** It comes from a file in the repository being tested and still lands in a
  `docker run` argv as a positional argument — on the far side of the wire now — so it is checked
  here before it is sent and to the same standard: `CiIdentifiers.requireImage` rejects blank and
  anything starting with `-`. Deliberately loose otherwise — which registry hosts, tags and digests
  resolve is the registry's business. **Two checkpoints, one rule each side owns**:
  `ContainersIdentifiers` checks it again where a refusal can be a 400 that names the field, and
  neither check is redundant, because a value refused here never leaves and a value refused there
  never reaches a daemon. This is hardening rather than a fix: no exploit through it is known
  (the orchestrator assembles its argv element by element and through no shell),
  and "the argument parser will surely never take this for a flag" is not a claim worth
  re-defending.
- **The step script.** It is code from a repository, and **qits-ci never executes it.** No code
  path here runs repo-controlled code as a host process, and none runs it through `docker exec`. A
  script leaves this process as a field of one JSON frame, on a socket the step container's own
  daemon dialled outbound, and executes as that daemon's child inside a sandbox with
  `capDropAll`, `noNewPrivileges` and resource caps. qits-ci's whole container vocabulary is
  lifecycle — put one here, read its log and remove it, remove this owner's own — and `exec` is not
  in it and cannot be: it is not on the orchestrator's wire at all, not even as a way to deliver the
  daemon binary. **This service now spawns NO program whatsoever**: reading a repository's config is
  an HTTP call to the git host, starting a container is an HTTP call to qits-containers, so there is
  no `git` and no docker CLI on the host and nothing left that could be handed pipeline content.

  `bash -c <anything from a repository>` appearing anywhere in this repo, in `src/main` or
  `src/test`, host-side or inside a docker argv or a workload spec, is the regression this paragraph
  exists to make unambiguous. The grep is `grep -rn "bash -c\|PRELUDE_FAILED\|docker exec"` over both modules; it
  must find nothing that executes.

  **"A step container never gets a docker socket" was this section's invariant and it is now false.
  What replaced it is narrower and was chosen deliberately, not conceded:** a step container never
  gets one *silently*. A step declares `docker: true` in `.config/qits/ci-post-receive.yml`, the
  spec carries `hostDockerSocket` for that step and no other, qits-containers is what mounts it (the
  socket's path is that service's deployment fact now — `qits.ci.docker-socket-path` is gone), the
  config diff shows the declaration, and the run row records that step like any other. Such a step is
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

`ci/src/main/resources/db/ci/migration/`, hand-written, its own lineage on its own datasource —
**PostgreSQL**, provisioned by this repository's own deployment spec and never shared with another
context's database or migration history.

**The ordinary rule is back: keep appending, never edit an applied one.** `V2__run_causation.sql`
and `V3__release_join.sql` are that rule being followed. The second adds the release join's two
tables — `ci_release_announcement` and `ci_scm_release`, see "The release join" — and touches
nothing that was already there. The first adds `ci_run.causation_id`, the platform's generic CausedRow column,
nullable, no backfill (`trigger_event_id` keeps the history) and part of no constraint. **For a
bus-arrived event the value is set EXPLICITLY in `acceptEventRun`, not left to the stamp** — the
row is written on `ci-trigger-worker`, behind the queue hop where the ambient scope has already
died, so the entity listener would record null; measured on the first live event runs of
2026-08-10, empty `causation_id` beside a full `trigger_event_id`. The stamp covers the manual
trigger, which evaluates on the request thread under the REST filter's restored scope. The
causation decisions themselves are enforced by `ArchRulesTest` in the `ci` module: every `@Entity`
here implements `CausedRow` (CiRun) or declares `@Uncaused` with its reason in the javadoc (CiStep
— its run carries the cause, and its row is written on the run worker where no scope stands;
CiDaemonPin — `event_id` is already the adopting event; CiReleaseAnnouncement — `trigger_event_id`
is already the cause, and it is on the row because the published event is stamped with it;
CiScmRelease — `event_id` is already the announcing release). A new entity that skips the decision
fails the build naming the class.

`V1__init.sql` is the rest of the schema. The nine H2 migrations it replaces (V1-V8 plus a Java V9) are
history in this repository's log and are not a prefix of this lineage: the move off H2 is a
re-bootstrap rather than a data migration, so no postgres database anywhere ever ran them and no
`V10__move_to_postgres.sql` had a reader. Read that file's header before adding anything — it argues
each of the schema's remaining decisions, and the check constraints in particular.

**What the H2 lineage taught, kept here because the lessons outlive the files.**

- **Backfilling a `not null` column into a live table** (V3): add it *with a default*, so the
  `alter` writes every existing row correctly, then `drop default`, so a future insert that forgets
  the value fails loudly rather than getting a silent one. The fresh V1 needs neither step, since
  every database reaching it is empty — but the next such column will.
- **Widening a check constraint the original script never named** (V4). V1 declared its status
  domains inline — `status varchar(32) not null check (status in (...))` — so H2 generated the
  names, and there is no portable way to drop an anonymous constraint. V4 could name `CONSTRAINT_76`
  only because it had *measured* that one database. Its replacement was named, and it wrote one
  `QUEUED` row and deleted it again as a probe: a database whose V1 check had landed under a
  different generated name would have taken the drop as a no-op and then rejected every accepted run
  at insert — silently in every JVM test, loudly only in the deployment. **If you ever declare a
  constraint, name it.**
- **The defect that ended the whole story.** H2 2.4.240 keeps a checked IN-set tied to the session
  that compiled it: once the pool retires that session, a valid write fails with `23514 Check
  constraint invalid`. V5 dropped the three checks it could name — but two of those names never
  existed, so `ci_step` kept its generated one, and on a freshly bootstrapped platform every `insert
  into ci_step` failed 23514 after a few long builds and runs died step-less. `V9` had to be a
  **Java** migration reading `INFORMATION_SCHEMA.TABLE_CONSTRAINTS`, because the generated names
  depend on the order the DDL was replayed and no script can name what it cannot know.

**Postgres has none of that defect, and the checks did not come back anyway.** `ci_run.status`,
`ci_run.trigger_type` and `ci_step.status` are catalogues that have grown once already — V4 added
`QUEUED`, `EVENT` joined `POST_RECEIVE` — so the invariant lives where the writes are:
`CiRunStatus`, `CiTriggerType` and `CiStepStatus` are `@Enumerated(EnumType.STRING)` and no code
path writes a status any other way. **A new status value is one enum constant and no migration.**
V8's `ck_ci_daemon_pin_verdict` is the one check that stays, because a verdict is a closed statement
about one probe's outcome rather than a growing catalogue — and because it is named, so widening it
would cost one line. `CiSchemaTest` runs the real migration against a real postgres and pins all of
that, including that the unbounded columns came out `text` and not a large object.

The locations list is shipped **once**, in the jar's `META-INF/microprofile-config.properties`, with
no copy in either test resources file — and it names one directory now that there is no Java
migration to point at. `baseline-on-migrate` is gone with the H2 file it existed for.

## Authentication

**Two identity tracks, and nothing in this repo implements either.** Both arrive in the
`qits-auth-core` jar (`integrations/qits-integrations-quarkus/`), and `service/pom.xml` says so in a
comment beside the dependency. There is no `security` package here any more, and no filter.

**Users** are headers. `ForwardAuthMechanism` reads `X-Qits-User` and `X-Qits-Roles` into a
`SecurityIdentity`; this service authenticates no person. The platform edge establishes the
session, and `qits-gateway` strips and re-asserts its reserved `X-Qits-*` namespace. That hygiene is
the entire reason the headers can be trusted here. There is no auth variant to select in this
service: the human-facing controllers require `qits:admin`; machine controllers retain their
separate `MachineAuth` audience/scope checks pending endpoint-scoped machine roles.

**`identity.isAnonymous()` is not a security state** — it means "no name for the audit row". A check
of the form `if (identity.isAnonymous()) deny` would look like a security control and be worth
nothing. It is also not the machine question: a machine caller is a `JsonWebToken` principal, which
is what `MachineIdentity.isMachine` reads and what makes an anonymous *user* and an absent *token*
two different facts.

**Machines** are a bearer, and the guard is `MachineAuth`. qits-idp mints the token; quarkus-oidc
validates its signature, issuer and expiry; `MachineAuth` then asks the two questions this service
owns — is it addressed here (`aud` contains `qits.auth.machine.audience`, which
`application.properties` pins to `qits-ci` because it is this service's identity and not a deployment
fact), and does its `project` claim cover the target. A missing claim is a mismatch, never a
wildcard; `project=*` on the **token** covers everything, but `"*"` as the *target* is compared like
any other string, so a caller cannot widen its own check. Failures are 401 with no machine token and
403 with the wrong one, both mapped by quarkus-security rather than by `CiExceptionMapper`.

**It sits beside forward auth rather than instead of it.** A request with no `Authorization` header
is not challenged — the tenant is bearer-only — so it falls through to the header mechanism and stays
user traffic.

**One platform-wide gate, `qits.auth.machine.required`, shipped `false`, and there is no third
state.** Off, every `require*` call returns at once, `quarkus.oidc.tenant-enabled` is off with it, no
JWKS is fetched and a clone-alone `./mvnw verify` needs no issuer anywhere. That is what let this
service ship enforcement before qits-idp was deployed. On, the same call demands a validated token —
and a deployment that turns it on with no audience configured fails at **startup** rather than
accepting tokens meant for another service. Which endpoints call the guard, and what a new one owes,
is under "Addressing"; the deployment steps are in `README.md`.

**This service presents a credential to two hops now, and both are the same identity.** It asks
qits-idp for a token of its own to present to **qits-containers**, and it presents the same client
id and secret as HTTP Basic to **qits-idp itself** to commission one credential per run (see "The
credential is commissioned per run"). One is a bearer this service holds, the other is a credential
it mints for a container; both live or die with `quarkus.oidc-client.client-enabled`.

qits-containers guards
every route — reads included — on the caller's own identity: its `OwnerGuard` compares the token's
`sub` to the owner in the path. So `quarkus.oidc-client.client-id` is not a label here, it is this
service's **owner string**, and `qits.ci.containers.owner` defaults to reading it.
`containers/ContainersClientProducer` is where the token becomes a header.

One switch, `quarkus.oidc-client.client-enabled`, shipped **false**, exactly as its predecessor was:
off, the extension builds a disabled client, the process boots with no secret and dials nothing, and
the calls go out bare — which is what the orchestrator's own gate (`qits.auth.machine.required`,
also off) expects. It stays independent of the inbound gate: either end of a hop is switched on
first. **It is also the commissioning switch**: `IdpCommissioner.enabled()` reads the same key plus
both halves of the credential behind it, so a deployment that has not turned the oidc client on
commissions nothing and a step container's environment is what it always was.

**This is a NEW arrangement rather than the old one coming back.** The retired one was
`notify/PdBearer`, a bearer for qits-platform-deployments' HTTP intake, and it went with the call it
carried when the deployer started subscribing to `BuildSuccessful`. What the pom said in the gap —
"adding the extension back means a new outbound caller, not a config change" — is exactly what
happened: a new caller arrived, so the extension did.

**The fetch is bounded.** It sits on the run worker, so the producer writes
`getTokens(oidcClient).await().atMost(…)` and never any `…AndAwait` spelling; `TokensHelper` caches
and refreshes, so a restarted idp pauses new issuance and nothing else, and a fetch that fails costs
the **header** rather than the call — the `TokenSource` contract is that a source which throws is a
source that returned nothing, so a broken idp is a 401 from qits-containers rather than an exception
on a build slot.

**`MachineGuardTest` blanks `qits.auth.forward.dev-user` in its profile, and that is not tidiness.**
Under `%test` the forward-auth mechanism answers every request carrying no `X-Qits-User` with a
synthetic `dev` identity, so it authenticates first and the bearer is never consulted. Left set,
every case in that file sees a user rather than a machine and the 401 cases pass for the wrong
reason. A real machine call has no such header and no such fallback.

`ForwardAuthTest` lives in qits-auth-core with the mechanism it covers, and its argument is worth
knowing when you read it: it sets a real `X-Qits-User` rather than reaching for `@TestSecurity`,
because the header **is** the contract. `MachineGuardTest` goes the other way and uses
`@TestSecurity` with `@OidcSecurity` claims on purpose — what is under test here is this service's
decision about a token's claims, while whether a signature or an expiry is checked is quarkus-oidc's
contract, tested where it lives.

## Tests

- App-level config lives in `service/src/main/resources/application.properties` — this module is the
  deployable, and Quarkus merges that file into the test config rather than letting
  `src/test/resources/application.properties` shadow it. **Never re-declare an app-level setting in
  test resources**: a suite green because the *test* copy is right proves nothing about what ships,
  and the two silently drift. `src/test/resources/application.properties` carries only genuine
  test-only overrides (`clean-at-start`, `target/` working dirs, a git-host url nothing answers on).
- **The databases in the suites are a real PostgreSQL, spawned as a child process.** Zonky resolves
  postgres binaries as ordinary Maven artifacts, so the clone-alone, docker-free rule survives the
  move off H2: never Testcontainers, never a dev service (`quarkus.devservices.enabled=false` says
  so out loud in both modules). `testdb/EmbeddedPg` starts ONE instance per surefire JVM and tracks
  its port in a **system property**, because a Quarkus run loads config sources in more than one
  classloader and a static field is not shared across them; `testdb/EmbeddedPgConfigSource` hands the
  url/username/password to every `@QuarkusTest` at an ordinal above `application.properties`, since
  the port is chosen at run time and cannot be written into a file. Every (module, datasource) pair
  names its own database — `ci_domain`, `ci_svc`, `eventstream_svc`, and the IT's `ci_packaged_it` /
  `eventstream_packaged_it` — so two suites on one host can never mean the same one. The suites set
  the datasource VALUES rather than the `QITS_RESOURCE_*` variables, deliberately: a suite that had
  to export the variables could not also say what happens when they are missing.
  `CiPackagedSurfaceIT` is where the shipped expressions themselves are exercised, and it passes the
  variables through **system properties**, not a static field, for the two-classloader reason above.
- **The git host in the suite is `githost/StubGitHost`, and it is a server rather than a
  directory.** Reading pipeline config is HTTP now (below), so the old stand-in — a `file://`
  directory laid out as `<base>/git/<repoId>` — answers nothing, and the shipped test config points
  `qits.ci.git-host-url` at `http://127.0.0.1:1`: an address nothing listens on, so a suite
  that never seeds a repository fails its reads fast and honestly. A suite that *does* declares
  `@WithTestResource(value = StubGitHost.class, scope = TestResourceScope.GLOBAL)`, which is how the
  port reaches the application's config **before it boots** — the same arrangement as
  `StubEventsServer`, and `GLOBAL` so it costs no restart. The stub still serves ordinary bares the
  suites build with real `git`; it shells `git` to answer the two content routes, so what is faked is
  the wire shape and nothing else. The ITs call `StubGitHost.start(root)` from their profile instead,
  because they own their own root directory.
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
  opposite of why the file is committed. **Nothing is hidden any more.** The one operation that was,
  `POST /ci/api/events/post-receive`, is gone with the HTTP fan-out it served — it was machine-only
  and its wire contract lived in the git host's repository, which is exactly what the criterion keeps
  out. `POST /ci/api/events/trigger` is the same criterion answering the other way and was always in:
  a person invokes it on purpose and its contract is written down nowhere but here. The guard the two
  shared had nothing to do with either decision.
  **`GET /ci/api/daemon` is in for the mirror-image reason** and is worth having as the worked case
  of a *machine* consumer that still belongs in the document: it is unguarded, its contract lives
  here rather than in the service that reads it, and qits-artifacts' daemon GC reads it fail-closed —
  so a change to its shape stops a sweep in another repository, which is precisely the class of
  change that must not land with an empty diff. "Machine surfaces stay out" was never about the
  caller being a machine; it is about where the contract is written down.
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
  datasource EXPRESSIONS — both of them, handed the `QITS_RESOURCE_*` variables rather than the
  datasource keys — Flyway's migrations surviving as resources, SnakeYAML and Panache on a real run,
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
- **The candidate list is proven at three levels, and each one can only say its own thing.**
  `HttpGitHostRepoListingTest` is plain JUnit against a real server on a real socket: the url shape,
  the id filter, the cache, and every way the read can fail answering the *empty* set rather than
  throwing. `ListedAndKnownCiReposTest` is a `@QuarkusTest` in the `ci` module, because the union's
  whole content is which beans it composes — `KnownCiRepos` by its own type past its `@DefaultBean`,
  the port through an `Instance` — and a hand-wired instance would prove none of it. And
  `CiManualTriggerTest`'s last case is the production gap itself: a repository seeded onto the git
  host with a trigger file, asserted to have **no run row**, firing a run off a hand-supplied event. Only the whole engine can show that, which is why it lives there and not
  beside the listing.
  There is a `FakeGitHostRepoListing` in each module's test sources, duplicated for the reason both
  `FakeCiStepRunner`s are. The service module's is a `@Mock`, so the real client is out of the way of
  every other test; the `ci` module's is an ordinary bean, because that module ships no
  implementation of the port at all and this is what makes it resolvable there. **Both default to
  empty**, which is not a neutral default but the interesting one: an empty listing is exactly what an
  unreachable git host answers, so the fallback needs no failure to stage.
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
  the real push path and asserts the *wire* contract the other side was built against: one PUT per green
  run, a v4 UUID in the path, `name` as the signature, and the run's coordinates in the canonical
  payload. Retries, the outbox and the three-way PUT semantics belong to the eventstream suite; the
  round trip through a real qits-events belongs to the platform.

  **One thing bites in both, and it is the shared candidate list.** Every repository either class has
  ever seeded is a candidate for every frame the trigger engine evaluates, for the life of that
  Quarkus instance. So a trigger file in a test fixture must select something **unique to the
  repository that committed it**, or one test method's event fires an earlier method's repository and
  "exactly two runs, exactly two publishes" stops being a statement about the test making it.
- `CiPipelineBoundaryTest` starts at an `SCMPublishCommit` handed to `ScmPublishCommitListener`, not
  at a `git push`, because the git host is qits-githost. Assertions about which refs it announces —
  and about the tag and delete events nothing here subscribes to — belong there. `bus/ScmPushFrames`
  builds the frame from the real record, so a payload change is a compile error rather than a suite
  that keeps passing against bytes nobody sends.
- `CiDaemonGateIT` is **the** gate: the outline's whole lifecycle through the real push path, on
  real containers. A two-step pipeline pushed into a real bare, the push announced, live chunks read
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
- `CiRestartReconciliationTest` is the boot half of the same story, and the only test that drives
  **both** startup observers at once: two runs left `RUNNING`, then `CiDaemonLauncher.destroyAllOwned`
  and `CiRunService.sweepInterrupted`, then the DELETE asserted to have carried `ci-step` and the
  boot instant, the push run `FAILED` and the event run recovered from its own snapshot.
  **It is a plain `@QuarkusTest` now and needs no docker**, which is the cutover paying for itself:
  its predecessor (`CiRestartReconciliationIT`, tagged `extended`) started real containers, and its
  one irreplaceable assertion — that an **unlabelled** bystander survived — was the whole of what a
  host-wide label filter needed proving about. There is no host-wide filter left, so there is no
  bystander a call from here could reach even in principle; the real-docker proof lives in
  qits-containers' own suite (`ContainersRestartAdoptionIT`), and a cross-service docker IT here
  would be that repository's test wearing this one's tags. What is qits-ci's own — the order, the
  owner, the workload, the instant — is provable against a socket.
  It sits in `control` rather than in `daemonhost` because `sweepInterrupted` is package-private
  there and the paired assertion is the point; everything it needs from the launcher is public. It
  swaps the injected launcher's client for a stub past `ClientProxy.unwrap` and puts it back in a
  `finally`, which is the same local ugliness `CiDaemonGateIT` documents and the same "not a pattern
  to spread".
- **The three `extended` ITs need a running qits-containers now**, and it is one more precondition of
  the same kind as docker and `-Dqits.ci.daemon-binary`: they `assumeTrue` a TCP connect to
  `qits.containers.url` and **skip** without one. The harness recipe is in `CiDaemonGateIT`'s javadoc
  — run the `qits/containers` image with the host's docker socket on `qits-net`, publish a port, and
  pass `-Dqits.containers.url=http://127.0.0.1:<port>`. They were not run in the cutover commit:
  they also need a built daemon binary, which is a different repository's release.
