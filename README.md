# qits-ci

The **in-repo CI pipeline**: a repository opts in by committing
`.config/qits/ci-post-receive.yml`; when a push lands, ci reads that config back out of the pushed
commit, runs each step's script in a fresh container of the step's declared image, and records a
per-step pass/fail for the push — advisory, queryable over REST.

There is a **second trigger**. A repository may also commit `.config/qits/ci-event-*.yml` files,
each naming a domain event on the platform's bus and a selection over its payload; a matching event
runs that file's pipeline against the head of `main`. That is the release train — a library
releases, the repositories that declared an interest build themselves — and every hop of it is
recorded, with the event that caused it, on the run and in the event log.

    git submodule update --init   # the Angular client at service/src/main/webui
    mvn verify                    # resolves qits-eventstream 1.0.0 from local qits-artifacts

## Layout

| Module | What |
|---|---|
| `ci/` | `eu.wohlben.qits.ci.*` — entity, persistence, dto, mapper, control, error. The pipeline itself. No web, no JAX-RS. |
| `service/` | `eu.wohlben.qits.ci.api` — the run read surface, the manual event trigger and the exception mapper — plus `…ci.bus`, where a push arrives as `SCMPublishCommit`, and `…ci.daemonhost`, the step-container control plane (below). There is no filter in front of the one write: it calls `MachineAuth` (qits-auth-core) itself. |
| `ci-daemon-protocol/` | `eu.wohlben.qits.cidaemon.protocol` — the ci-daemon wire contract, **vendored** from [qits-ci-daemon](https://github.com/QuicklyIterateTheSoftware/qits-ci-daemon) and never edited here. Framework-free; `diff -r` is the drift detector. |
| `ci-events/` | `eu.wohlben.qits.ci.events` — the events this service announces: `BuildSuccessful` for every green run, `SoftwareRelease` once per artifact a release pipeline declared. Depends on the published `qits-eventstream` jar and nothing else. |

`ci/` is a library jar. **`service/` is the application** — it carries
`<packaging>quarkus</packaging>` and produces a process, as a JVM fast-jar or as a native binary:

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar   # :8080, reads under /ci/api, client at /ci

    ./mvnw package -Dnative
    ./service/target/qits-ci                              # same routes, ~0.2s to listening

**Native is the shipping form.** `.sdkmanrc` names a GraalVM (`25.0.2-graalce`), so `sdk env` alone
is enough toolchain: the build wants a `native-image` on `GRAALVM_HOME`, `JAVA_HOME` or `PATH`, and
finding none it does not fail — it falls back to pulling a 1.8 GB Mandrel image and compiling under
docker. That fallback still works and is what a GraalVM-less CI gets; it is just not the intended
path, and it is worth recognising by name when a build that normally takes two minutes starts
downloading a container image. Note the coincidence: this service *causes* a container to run, per
step, by design (below) — through qits-containers, and the **build** must not touch docker at all.

Most of the 0.2s is connecting to postgres and running Flyway; the framework itself is up in
milliseconds. That is the point of packaging it this way — a restart is a non-event rather than a
window in which pushes arrive and record nothing.

It was extracted as a library, on the assumption that a consuming Quarkus application would pull it
in and gain the routes. That application was never written and under the gateway topology never will
be. `ci` owns its **own datasource, persistence unit and Flyway lineage** (`db/ci/migration`, one V1, on
its own postgres database), which is what makes this a standalone deployable rather than a checkout
of the monorepo. The directory names are `ci/` and `service/` because the extracted git history is
anchored to them; the maven coordinates are `eu.wohlben.qits:qits-ci-domain` and `…-service`.

## Addressing

Every route this service serves lives under **`/ci`**, its gateway segment — the service name
without the `qits-` prefix. qits-gateway routes *verbatim by prefix*, `/ci/*` → qits-ci with the
path untouched, so the prefix is not decoration the gateway strips: the service has to serve it,
and there is no unprefixed form. Service-to-service calls on `qits-net` bypass the gateway and
address the same paths.

Beneath the segment sits the kind of surface: `/ci/api/…` for the JSON API
(`quarkus.rest.path`), `/ci/q/…` for what Quarkus itself serves —
`/ci/q/openapi`, `/ci/q/swagger-ui` (`quarkus.http.non-application-root-path`, which is outside
`quarkus.rest.path` and so has to carry the segment separately).

`/ci/` itself is the **Angular client**: the
[qits-spa-ci](https://github.com/QuicklyIterateTheSoftware/qits-spa-ci) submodule at
`service/src/main/webui`, built by Quinoa into the packaged artifact and served from it, with deep
links under `/ci/` falling back to `index.html` so the client's own router handles them. Bare `/ci`
(no trailing slash) is a 301 to `/ci/` (`WebUiRedirect`, GET/HEAD only, query preserved) — Quinoa
mounts the SPA at `/ci/*`, which does not match the bare segment on its own.

That fallback is a catch-all, and what it must **not** swallow is named in
`quarkus.quinoa.ignored-path-prefixes=/api,/q,/daemon` — relative to the UI root, and repeating
`/api` and `/q` because setting the key *replaces* Quinoa's own derivation (which reads
`quarkus.rest.path` and `quarkus.http.non-application-root-path`, and nothing else) rather than
extending it. The third entry is why the key is set at all: `ws://…/ci/daemon` is a `@WebSocket`
literal outside that derivation, and websockets-next claims only the upgrade — so before this key
existed a plain `GET /ci/daemon` answered `200 text/html` with `index.html`, which the machine
client on the far side parses as data. With it, a mistyped machine path answers 404 and the upgrade
is untouched. Add a literal route, add its prefix.

Nothing under `/ci/api` repeats `ci` again: the segment already said it. That is the one shape
change here beyond the prefix, along with runs becoming their own entity (below).

## The boundary

Runs reference repositories **by string id and branches by name, never a foreign key** — a deleted
repository simply leaves runs behind as dangling history. Everything this context needs from the
rest of qits it reaches over a URL it is configured with:

| Direction | Surface | Config |
|---|---|---|
| in | `SCMPublishCommit` off the event bus — one per updated branch ref of a push, carrying the head commit's metadata and `suppressCi` | not an HTTP surface and not machine-guarded: what authenticates an event is the bus that carried it. Consumed durably as `ci-push-runs` (`bus/ScmPublishCommitListener`) |
| in | `POST /ci/api/events/trigger` — `{name, payload, occurredAt?, eventId?}` → 200 `{eventId, runIds, repositoriesRead, repositoriesSkipped}`, one domain event supplied by hand instead of by the bus; it **evaluates before it answers**, and a 503 means it could not ("Triggering one by hand") | guarded on the same resource, and it needs the wider grant: a machine token covering **every** project, because the event names no repository |
| in | `GET /ci/api/runs?repositoryId={repoId}[&limit={n}]`, `GET /ci/api/runs/{runId}` | not machine-guarded; they carry build logs, so a deployment must keep them behind its auth policy. **Every read here takes `qits:admin` OR `qits:system`** — `qits:system` is the machine role and `qits:admin` the human one, and a peer polling a run it asked for (qits-platform-maintenance waits out a bump this way) must not be granted a person's role to do it. `POST /ci/api/runs/{runId}/cancel` is not widened with them and stays `qits:admin` |
| in | `GET /ci/api/runs/active` → `{"runs": [...]}` — every `QUEUED` or `RUNNING` run on the instance, all repositories, newest first, no parameters | same; unscoped, because "what is CI doing right now" has no repository to scope to |
| in | `GET /ci/api/repositories` → `{"repositoryIds": [...]}` — the distinct repo ids this instance has runs for, ascending | same; it is the one read here that is not scoped to a repository, because it answers *which* |
| in | `GET /ci/api/repositories/summary` → `{"repositories": [{repositoryId, projectId, repoName, lastRun, lastMainRun}]}` — ascending by id, full run objects, `lastMainRun` null when there is none, and the name pair null for a repository whose pushes were id-addressed | same; it is the id listing plus the two runs a client would otherwise make a request per repository to find |
| in | `GET /ci/api/daemon` → `{"daemonName", "daemonVersion", "previousDaemonVersion", "source"}` — the pin ladder's top rung (an adopted release, else the configured pin), never a run row; blank `daemonVersion` and `source: "none"` mean this deployment has pinned none | same; read fail-closed by qits-artifacts' daemon GC and readable by the client |
| in | `POST /ci/api/runs/{runId}/cancel` → 202, 409 on a run that has already finished | same: no machine guard, behind the deployment's auth policy |
| in | `ws://…/ci/daemon` — the socket each step container's daemon dials **out** to | authenticated by a host-minted per-container secret, not by a machine token |
| out | where the git host answers: ci reads a commit's pipeline config off its content routes — `<base>/git/<projectId>/<repoName>/blob/<rev>/<path>` and `…/tree/<rev>[/<path>]` for a run whose push carried the public pair, `<base>/git/<repoId>/…` for one that did not — and, with no `qits.ci.projects-url` set, its candidate listing off `GET <base>/git` → `{"repositories": [...]}` | `qits.ci.git-host-url` |
| out | `GET <base>/projects/api/repositories` → `{"repositories": [{id, projectId, name, mainBranch}]}` — the candidate list an arriving event is evaluated against, and the only place the public `(projectId, name)` pair can be read. **Unset by default**: with no value ci falls back to the git host's storage listing, which is what a pre-cutover platform and a clone-alone build need | `qits.ci.projects-url` |
| out | the same, as reachable **from a step container** on the shared network | `qits.ci.container-git-url` |
| out | the same content routes again, at one repository's `main`, for the **platform** trigger files `.config/qits/ci-platform-event-*.yml` — one listing per arriving event; **blank turns it off and reads nothing** | `qits.ci.platform-pipelines-repository` (default `qits-qits`) |
| out | where a step container downloads the daemon binary from | `qits.ci.daemon-binary-url-template` + the pin ladder's answer (`qits.ci.daemon-version` is the ladder's bottom rung, never demoted) |
| out | `PUT /events/api/events/{uuid}` — one `BuildSuccessful` per **green** run, idempotent (the `RunAnnouncer` seam), and the **only** thing a green run announces | `qits.events.url`, `qits.eventstream.enabled` |
| out | the same route — one `SoftwareRelease` per artifact a green **release pipeline** declared (the `ReleaseAnnouncer` seam), and **only once an `SCMRelease` for the same (repository, version) has been seen** — see "The release join" | the same two keys |
| out | `ws://…/events/stream` — dialled out and held open, carrying what qits-events broadcasts back | the same two keys; the address is derived, never configured twice |
| out | `PUT/DELETE /containers/api/containers/<owner>/ci-step/<ref>` — every step container: started, read and removed through qits-containers, which owns the docker daemon. **qits-ci holds no docker socket.** | `qits.containers.url`, `qits.ci.containers.owner` |
| out | `POST/DELETE/GET /idp/api/clients` — one commissioned oidc client per run, minted at its first `docker: true` step and deleted when the run closes; the pair a publishing step pushes with | `quarkus.oidc-client.auth-server-url` + `…client-id` / `…credentials.secret`, `quarkus.oidc-client.client-enabled` |
| out | the registry a publishing step pushes to, as `$QITS_REGISTRY` and `$QITS_IMAGE_REPOSITORY` in **every** step container — dialled by the *host's docker daemon*, never by this process | `qits.artifacts.registry-host`, `qits.artifacts.image-repository` |
| out | the npm registry roots, as `$QITS_NPM_REGISTRY_URL` (hosted, `@qits/*` publishes) and `$QITS_NPM_PROXY_URL` (the npmjs pull-through cache) in **every** step container — dialled by the *step container itself* on the shared network | `qits.artifacts.npm.hosted-url`, `qits.artifacts.npm.proxy-url` |
| out | the hosted Maven repository root, as `$QITS_MAVEN_REGISTRY_URL` in **every** step container — also dialled by the step container on the shared network | `qits.artifacts.maven.registry-url` |

The run listing takes the repository as a **query filter, not a path segment**. ci does not own
repositories, so `/repositories/{repoId}/runs` asserted a containment this context does not have —
and put three services under one gateway prefix. `runs` is the entity; `{runId}` stays in the path
because there it is identity rather than scope.

That filter is mandatory, which makes `GET /ci/api/repositories` the answer to the question it
raises: *which* repositories are there to filter by. It returns `repositoryIds` and not
`repositories` because ci holds no repository object — `ci_run.repo_id` is a plain string with no
relation to anything, and these are ids this instance **observed**. It is deliberately narrower than
the trigger engine's candidate list, which also counts whatever the git host lists: a repository ci
has only been told about has no run history to read. Without this endpoint, CI activity that no other
service claims is invisible to a client rather than merely unattributed — and on this platform, where
`qits-local-up.sh` seeds the platform's own repositories straight onto the git host with no
qits-projects row, that is the whole run history.

`?limit={n}` is optional and takes the newest `n`; absent, the listing is unbounded, so nothing that
predates it changes. The ordering (`createdAt desc, id desc`) is what makes "the newest n" a total
answer rather than an arbitrary sample. There is deliberately **no `?offset=` and no cursor** — an
offset over a list that grows at the head re-shows rows under concurrent inserts, and the two things
anyone wants (the newest n, then one specific run) are both already covered. A real history walk
wants `before=<createdAt>`, and that waits for a requirement.

**Two reads exist because the alternative was a request per repository.** `GET
/ci/api/repositories/summary` is `GET /ci/api/repositories` with, per id, the repository's newest run
on any branch (`lastRun`) and its newest run on `main` (`lastMainRun`, null when it has none, and
frequently the same run as `lastRun`). A client drawing "which repositories are there and how is each
doing" used to call the id listing and then one bounded run listing per id — n+1 round trips over the
gateway for one question. Both slots carry the **full** run object, minus `steps` and `live` as every
listing here is, because a second "run summary" type would drift from `CiRunDto` for nothing. The
older endpoint keeps returning bare `repositoryIds` and keeps its name: it names ids, this one names
objects about *runs*, and neither claims ci owns a repository.

`GET /ci/api/runs/active` is the other one: every run on the instance that is `QUEUED` or `RUNNING`,
across all repositories, newest first, no parameters. It is the only read here that is not scoped to
a repository *and* not a listing of them — "what is CI doing right now" has no repository to scope to,
and asking per repository would mean knowing the repositories first and still seeing a different
instant in each answer. It needs no `?limit=`: what is active is bounded by accepted work and the
configured worker pool, not by uptime. It became answerable only when a queued run became a row
(below).

The push sender is [qits-githost](https://github.com/QuicklyIterateTheSoftware/qits-githost), which
publishes one `SCMPublishCommit` per successfully updated branch ref through the qits-eventstream
outbox. qits-ci consumes it durably (`bus/ScmPublishCommitListener`, consumer id `ci-push-runs`).

**This replaced `POST /ci/api/events/post-receive`**, and the endpoint is gone rather than deprecated.
That call was fire-and-forget — the sender swallowed delivery failures at debug — so a qits-ci that
was down, restarting or mid-cutover when a push landed simply never built it, with nothing anywhere
to say so and "replay the post-receive by hand" as the only cure. A durable consumer reads the same
push back off the log instead. Two consequences worth stating:

- **`-o qits.no-ci` is not a suppressed event any more.** The git host used to decide for its
  consumers by not POSTing; the option now travels as `suppressCi` on the event, and this service
  is what decides that it means "record no run". A consumer that wants to act on a suppressed push
  (a backup trigger, say) can.
- **There is no address to keep in step.** Nothing in another repository spells a qits-ci path for
  pushes, so a prefix change here cannot silently stop CI. What can is a listener that does not
  subscribe, which is why `EventstreamDarknessTest` asserts the bean exists.

**The arrangement does NOT repeat one hop down, and it used to.** A green run was announced to
[qits-platform-deployments](https://github.com/QuicklyIterateTheSoftware/qits-platform-deployments)'s
`/platform-deployments/api/events/build-succeeded` by `service/…/notify/PdBuildNotifier`, behind a
`PdNotifier` seam in `ci/control`, on every green run. **That whole idiom is retired** — the
notifier, the `PdBearer` credential it carried, the `qits.platform.deployments.intake-url` key and
the port itself.

What replaced it is the pair below: qits-ci publishes `BuildSuccessful` through the eventstream
outbox, and qits-platform-deployments consumes that event with a **durable** subscriber which calls
its own announce path. Both halves are durable where the POST was fire-and-forget — an unreachable
qits-events leaves the event in this process's outbox, and a disconnected or restarting deployer
catches up from the log — so a deployment is delayed by an outage rather than lost to one.

That is worth the whole paragraph because the loss was measured. The call was one POST whose failure
was swallowed at debug, and a bootstrap paid for it: qits-platform-idp was redeployed minutes before
a green run finished, the single attempt hit the refusal window that left behind, and the deployment
never happened with nothing anywhere saying so. The first answer was bounded retries in the notifier
— 5s, 15s, 45s, 2m, then a warning — which narrowed the window and kept the shape: still
at-most-once past three minutes, still a POST that only this process knew it owed. The bus pair has
no such window, and the retries retired with the notifier that grew them.

**The deployer's HTTP intake stays.** It is the manual and recovery door, which a bootstrap replay
knocks on by hand; what went away is qits-ci knocking on it per green run. So this repository
configures no address for the deployer at all, and presents a credential to nobody — the
`quarkus.oidc-client` extension went with `PdBearer`.

**A green run is announced to nobody in particular.** The transition publishes a
`BuildSuccessful` event to [qits-events](https://github.com/QuicklyIterateTheSoftware/qits-events),
through the `RunAnnouncer` seam in `ci/control`, implemented by
`service/…/bus/BuildSuccessfulAnnouncer`. Only `SUCCESS` announces — a red run, a `CONFIG_ERROR` and
a discarded run announce nothing. It is a *statement* anything on the platform may subscribe to
rather than a request addressed to one service, which is exactly why the deployer could move behind
it without qits-ci learning anything about deploying. It is a
`PUT` at a UUID the publisher picks, so a retry is a replay rather than a duplicate; a delivery that
does not land goes to an outbox in this process and is retried on a schedule; and it carries the
run's own `finishedAt` as the event's `occurredAt`, plus `imageDigest` — which qits-ci never has,
since a step publishes an image from inside its own container and answers with an exit code.

**A green *release pipeline* announces one more thing per artifact it declared**: a
`SoftwareRelease`, through a third seam — `ReleaseAnnouncer` in `ci/control`, implemented by
`service/…/bus/SoftwareReleaseAnnouncer`. It is a separate port from `RunAnnouncer` because it says
something else: not "a build passed" but "this exact package is in qits-artifacts and you can
install it". It is also the one announcement that is **gated**: it waits for an `SCMRelease` for the
same `(repository, version)`, so a bootstrap replay restoring a release tag publishes silently. See
"The release pipeline, and what it declares" and "The release join".

qits-ci is also the **first consumer** of the same bus: `service/…/bus/BuildSuccessfulListener`
receives its own announcement back off `/events/stream` and logs it. Nothing hangs off that yet; it
is there because a producer nobody has ever seen consume is a bus with an untested second half.

Consuming has **three seams**, and qits-ci uses the third for all of its listeners.
`QitsEventListener<E>` names an event class and gets it deserialized; `QitsRawEventListener` names a
set of event *names* at runtime, may say `"*"` for all of them, and gets the frame itself. Both are
live-only and at-most-once: what is broadcast while a consumer is disconnected, restarting or
mid-cutover is gone. `QitsDurableEventListener` is the answer to that — the library claims each event
for each listener in one transaction with the handler, and pages a per-listener watermark forward
from the event log at startup and on a schedule, so a disconnect is a delay instead of a hole. The
subscribe frame is the union of all three, `"*"` collapsing it to `["*"]`.

**Every qits-ci listener is durable**, because each of them acts on something a lost event would
silently not do: a push that is never built, a release train that stops triggering, a daemon release
that is never adopted, a release nobody announces. Their consumer ids — the stable names their
bookkeeping is keyed on — are `ci-push-runs`, `ci-event-triggers`, `ci-release-train`,
`ci-daemon-adopt` and `ci-release-facts`.

**The trigger engine says `"*"` permanently**, so this service's
subscribe frame *is* `["*"]`: the event names it cares about live in other repositories' files and
change with every push, and a listener that waited to read config before naming anything would never
open the stream it reads config over. `BuildSuccessfulListener` no longer appears on the wire and is
unaffected, because dispatch filters and the wire never did.

**Every event carries a nullable `parentId`** — the event that caused it — so a release train is a
chain in the log rather than a set of rows distinguishable from coincidence only by their
timestamps. It is envelope data, stamped by `QitsEventBus.publish` and never declared by an event
class, and it is filled in from an explicit argument or from `CausationScope`, the ambient
thread-local the dispatcher establishes around each listener call. **Every run qits-ci records has a
cause and says so**: an event-triggered run's `BuildSuccessful` carries the event that triggered it,
and a push-triggered run's carries the `SCMPublishCommit` that announced the push — which is what
makes release → push → build → deploy one chain rather than two halves with a root in the middle.
A root is what is left for a run nothing announced. The rules that bite are in AGENTS.md under "The event bus" and, for the library itself, in
qits-eventstream's own AGENTS.md.

Both halves are **dark in `%dev` and `%test`** (`qits.eventstream.enabled`), the same posture the
OTLP exporter takes, and a deployment without a qits-events is a supported configuration in exactly
the way a deployment with no deployer is.

ci never touches the bare origins on disk, and it clones nothing: it **reads the one file** off the
git host's content routes — `GET <qits.ci.git-host-url>/git/<projectId>/<repoName>/blob/<rev>/<path>`
for a run that carries the public pair and `…/git/<repoId>/blob/…` for one that does not, with `rev`
a sha or a ref name and the resolved commit answered in a `Git-Commit-Sha` header. So it runs on a
machine with no shared filesystem with qits, no local mirror and no `git` binary.

**The push path reads at the pushed sha itself**, which is the whole of why there is no mirror: a
second push landing first changes nothing, and a 404 means the commit declares no pipeline. A commit
the repository no longer holds at all is told apart by one more read (the tree at that sha) and
records nothing, so a force-push cannot leave a red run blaming a commit whose build was never
broken. The event path lists `.config/qits/` at `main`, takes the head from the header, and reads
each file **at that sha**, so a push mid-evaluation cannot mix two commits into one run.

## The file a repository commits

`.config/qits/ci-post-receive.yml` is a list of steps and nothing else. Everything additive since
has stayed additive over that core:

```yaml
steps:
  - image: qits/build-images/maven-base:latest # required — the container this step runs in
    user: build                                # optional — else the image's own user (root)
    script: ./mvnw -B -ntp verify              # required — bash, run in the checkout
  - image: qits/build-images/ci-base:latest
    docker: true                               # optional, default false — see the warning below
    timeout-seconds: 3600                      # optional — else qits.ci.step-timeout-seconds
    branches:                                  # optional — else the step runs on every branch
      - exact: main
    script: |
      ref="$QITS_REGISTRY/$QITS_IMAGE_REPOSITORY/qits-gateway:$QITS_CI_SHA"
      docker build -t "$ref" -f docker/Dockerfile .
      docker push "$ref"
      docker rmi "$ref" || true
```

Unknown keys — top level or per step — are never read, so a repo may carry config for a newer
qits-ci. Keys that *are* known and unreadable (`timeout-seconds: soon`, `docker: yes-please`,
`branches: []`, `user: build:root`) are a `CONFIG_ERROR` run instead: a repo that meant to bound a
step, to ask for a socket, to scope a step or to drop root must find out.

### Running a step as somebody

`user:` is a passwd name or a bare uid, and it becomes the container's `--user`. Absent means the
image's own user, which is root for every base image the platform builds on.

**It is a declaration rather than something a script does, because a script cannot do it.** A step
container is started `--cap-drop=ALL`: without CAP_SETUID and CAP_SETGID `su` cannot switch user at
all, and without CAP_CHOWN even root cannot `chown` the checkout. Measured 2026-08-12, on
qits-containers' first real run — `chown: /workspace: Operation not permitted`. The only moment a
user can be chosen is the launch, which is what this key reaches.

**The image has to back the name with a passwd entry.** Docker accepts an unknown uid happily, but
anything calling `getpwuid` fails for a user that exists only as a number — and that is the reason
this key exists at all: zonky's embedded postgres refuses to `initdb` as uid 0 and looks the user up.
`maven-base` carries `build` (uid 1001) and pre-creates `/workspace`, since a non-root container user
cannot make a directory at the root of the filesystem.

**`user:` with `docker: true` is a `CONFIG_ERROR`.** A step holding the host's docker socket stays
root: the socket's ownership is the host's fact and not a repository's. Split the work into two
steps, which is what every publishing pipeline here already does.

### Binding a step to branches

`branches:` is a **list of matcher mappings**: entries are **OR**'d, and a mapping's keys are
**AND**'d — the `when:` DSL's composition rule minus the path level, because the subject is one
scalar, the branch the run is on. The whole vocabulary is **`exact`** and **`prefix`**. There is no
`exists` (a branch is always there, so it could only ever say yes) and no `regex` (`prefix:
maintenance/` spells the requirement that asked for this feature, with no anchoring, escaping or
ReDoS question).

```yaml
steps:
  - image: qits/build-images/node-base:latest   # no branches: — runs on every push
    script: npm ci && npm test
  - image: qits/build-images/node-base:latest
    branches:
      - prefix: maintenance/                    # …this one only on a maintenance push
    script: ./release.sh
```

- **Absent means the step runs on every branch** — exactly the behaviour every pipeline had before
  this key existed.
- **An empty list is a config error.** Both readings of `[]` already have an unambiguous spelling:
  omit the key for "every branch", delete the step for "none".
- **A step the branch does not bind is recorded `SKIPPED` and stops nothing.** No container is
  launched, the run's verdict is untouched, and the steps after it run. A run whose every step is
  branch-skipped is a trivially green run, like a pipeline with no steps.
- **"Release only if the tests passed" needs no machinery** — it is step order. A failing step still
  stops the loop, so a later scoped step is never reached.

The two kinds of `SKIPPED` are told apart **by the step's output**:

| Output | Why the step was skipped |
|---|---|
| `[step not bound to branch <branch>]` | its `branches:` did not bind this run's branch |
| *empty* | the loop never reached it — an earlier step failed, or the run was cancelled |

`branches:` on a step in a **`ci-event-*.yml` is a parse error**, naming the file and the reason:
an event-triggered run always builds the head of `main`, so `exact: main` there is decoration and
anything else is a step that can never run, which reads exactly like one that never got its turn.
A condition over the *event* is what `when:` already is.

**Publishing an image is not a feature here, it is a step.** Steps are sequential, so a push runs
only after the build steps went green; a failed push is a failed step is a **failed run**, so the
`BuildSuccessful` announcement (`SUCCESS` only) keeps implying the image exists. The tag is the whole contract
with qits-platform-deployments, which pulls `<registry>/<repository>/<application>:<sha>` where
`<application>` is by convention the repository's name — the script must spell exactly that, and the
only enforcement is the convention plus the deployer's `IMAGE_MISSING` telling on a mismatch.

> **`docker: true` makes that step root-equivalent on the host.** The workload spec carries a
> `hostDockerSocket` flag and qits-containers bind-mounts the host's docker socket into the step's
> container — where that socket lives is the orchestrator's deployment fact, not this service's — and the socket *is* the daemon
> and the daemon is root: such a step can mount host paths, start privileged containers and leave
> the sandbox at will. The `--cap-drop=ALL` / `no-new-privileges` flags stay on and still fence the
> step's own process tree, but they do not bound what the daemon will do on its behalf. It is
> accepted for the POC under the standing posture (the sources are trusted), and it is **opt-in per
> step**: a repository declares it, a config diff shows it, and every step that does not declare it
> keeps exactly the sandbox described below.

The build and the push both happen in the *host's* daemon — the step's CLI is only a client — so the
registry address must resolve and be trusted **from the docker host**, not from this process. The
step image supplies the docker CLI; the platform supplies the socket and the two coordinates.

**A registry that wants a login gets one, the credential is this run's own, and the step's script
says nothing about either.** An in-network registry answers an anonymous push; one behind the edge
answers it with a docker Bearer challenge, and the CLI then exchanges a *stored* username/password
for a short-lived token at the realm the challenge names — by itself, with no `docker login` in the
pipeline. So at the **first step of a run that declared `docker: true`**, qits-ci asks qits-idp to
commission a client for that run and hands the step `$DOCKER_CONFIG` pointing at a directory holding
the `config.json` the CLI reads, plus `$QITS_COMMISSIONED_CLIENT_ID` and
`$QITS_COMMISSIONED_CLIENT_SECRET` for a BuildKit secret mount. Every later docker step of the same
run reuses the pair; the run's end deletes it. A step without the socket is handed nothing — it
cannot push, so it has no use for a credential. The file lives under `/tmp`, never in the checkout,
so it can never reach a `docker build` context. It carries **one entry per host in
`qits.ci.docker-auth-hosts`** — the docker client picks a login by hostname, so a build that pulls
its base image from the mirror vhost and pushes to the registry vhost needs both named.

**There are no `qits.ci.registry-auth.*` keys any more.** The credential used to be one static pair
in a deployment's environment, shared by every run of every repository; a deployment still setting
them is setting nothing. What decides whether anything is commissioned is
`quarkus.oidc-client.client-enabled` — off (the shipped default) and a step container's environment
is exactly what it always was, which is the case a deployment on an anonymous registry stays in.

**A commission that could not be made fails the step**, naming the call, rather than launching a
step with no credential: an unreachable idp must not surface as a mysterious push 401 minutes later.

**Two BuildKit variables ride the same scope.** A step declaring `docker: true` also gets
`DOCKER_BUILDKIT=1` and `BUILDX_NO_DEFAULT_ATTESTATIONS=1`. Every step image ships buildx, so the
first turns a silent fall back to the legacy builder into a loud error, and the second keeps a push a
single manifest rather than an attestation index the platform registry does not expect.

**Publishing an npm package needs none of that.** `$QITS_NPM_REGISTRY_URL` (hosted — where `@qits/*`
is published) and `$QITS_NPM_PROXY_URL` (the pull-through cache of npmjs every install resolves
through) are injected into every step container alongside the two above, and the caveat on them is
the **opposite** one: they are dialled by the *step container itself* over the shared network, so an
npm publish is an ordinary HTTP step with no socket, no `docker: true` and no root-equivalence. The
consequence for a deployment is that the value which is right for these is the in-network alias — a
host-published mapping substituted for `$QITS_REGISTRY` (the local stack's
`registry.dev.localhost:8080`) must **not** be substituted for these, because a step container has
no such address.

A step writes its own `~/.npmrc` from the two, so no repository ever spells a registry address:

```sh
# The token line is npm-CLI ceremony only — the server reads nothing.
cat > ~/.npmrc <<EOF
registry=${QITS_NPM_PROXY_URL}
@qits:registry=${QITS_NPM_REGISTRY_URL}
${QITS_NPM_REGISTRY_URL#http:}:_authToken=qits-ci
EOF
```

Maven has the same network posture. `$QITS_MAVEN_REGISTRY_URL` is the hosted repository root a
release step passes to `mvn deploy` (for example with `-DaltDeploymentRepository`), and it is
injected into every step so neither publishers nor downstream version handlers spell the
qits-artifacts address.

Two lines there are worth reading twice. The `#http:` strip is parameter expansion, not a comment:
it turns the url into the `//host/path/` form npm keys credentials by, which is the one non-obvious
line in the whole preamble. And the token is **npm-CLI ceremony only** — the npm client refuses to
`publish` against a registry it holds no credential for (`ENEEDAUTH`, verified still enforced by
npm 10.9.4), a pre-flight that never reaches the wire; qits-artifacts requires no credential in
either direction on `qits-net` and reads nothing from that line. It is not an auth scheme and the
day npm accepts an anonymous publish it simply goes away. Keep the comment *outside* the heredoc —
inside it, the line lands in the written file.

**The `~/.npmrc` form only works for a repository that commits no `.npmrc` of its own.** npm and
pnpm rank a project `.npmrc` above the user one, so in a repo that commits registry routing (a
frontend pointing developers at the host-published port), the preamble above is written and then
silently ignored. Such a repo's step uses the environment form instead, which outranks both files:

```sh
env npm_config_registry="$QITS_NPM_PROXY_URL" \
    "npm_config_@qits:registry=$QITS_NPM_REGISTRY_URL" \
    pnpm install --frozen-lockfile
```

**Every step container is told which repository it is building, in both coordinate systems.**
`$QITS_CI_REPO_ID` is the git host's storage id, exactly as it always was — after the identity
cutover an opaque UUID — and beside it are `$QITS_CI_PROJECT_ID` and `$QITS_CI_REPO_NAME`, the public
pair `/git/<projectId>/<repoName>` is built from and the one a person reads. The two new variables
are **empty rather than absent** when the run's push was announced id-addressed, so a step reads one
shape whichever way its run arrived. `$QITS_CI_REPOSITORY_URL` — what the checkout clones — follows
the same rule: the public address when the pair is there, the id-addressed one when it is not.

**`$QITS_WORKSPACES_URL` is injected on the same reading**, and it is qits-workspaces' root —
scheme, host and port, no path. A step that asks for its own repository to be released after the
tests it follows went green POSTs to `$QITS_WORKSPACES_URL/workspaces/api/branches/release`
with `?projectId=$QITS_CI_PROJECT_ID&repositoryName=$QITS_CI_REPO_NAME` — the door takes the
**public** pair and refuses the storage id above the seam; the path is the caller's to spell, and
the address is never a literal in a pipeline. Like the npm pair and
unlike `$QITS_REGISTRY`, it is dialled by the step container itself over the shared network, so the
in-network alias is the value that is right for it.

**That POST must carry a bearer.** qits-workspaces runs with `QITS_AUTH_MACHINE_REQUIRED=true`, so
an unauthenticated call is answered 401 — and because the release is the *last* leg of the
maintenance bump train, a step that omits the header builds green and then strands its bump on its
branch, silently. The credential is the run's own commissioned client, the same pair the docker
block hands buildx and the same pair `qits-git-credential` exchanges: `POST
$QITS_GIT_AUTH_TOKEN_URL` with `grant_type=client_credentials`, HTTP Basic
`$QITS_COMMISSIONED_CLIENT_ID:$QITS_COMMISSIONED_CLIENT_SECRET`, and an `audience` naming
qits-workspaces. `$QITS_GIT_AUTH_TOKEN_URL` is the idp's plain token endpoint despite its name —
git's helper was merely its first caller, and it is the only idp address a step is given. The
audience is not injected today; a step derives it from the host of `$QITS_WORKSPACES_URL`, which is
the service alias qits-workspaces checks as its `QITS_AUTH_MACHINE_AUDIENCE`. A step that finds no
commissioned pair should send no header rather than fail — a deployment that commissions nothing
has machine auth off too.

## The other file a repository commits: `.config/qits/ci-event-*.yml`

A push is not the only thing that can run a pipeline. A repository may also commit **event
triggers**: files that name a domain event off the platform's bus and a selection over its payload,
and run their own pipeline when both hold. The motivating shape is the release train — a library
releases, every consuming SPA's event pipeline commits the version bump, each SPA's own release
fires the pipelines of the services embedding it — where each hop is one repository declaring, in
its own tree, which upstream events it cares about.

```yaml
# .config/qits/ci-event-ui-components-released.yml
event: BuildSuccessful          # the event NAME (its signature), matched exactly
when:                           # the selection — omit it to fire on every event of that name
  - repoId: { exact: qits-spa-ui-components }
    branch: { exact: main }
steps:                          # exactly the schema ci-post-receive.yml uses
  - image: qits/build-images/node-base:latest
    script: ./bump-ui-components.sh
```

- **The `*` is yours and is completely ignored.** It names the trigger for humans. A repository may
  have any number of these files; each is an independent trigger with its own pipeline, and two of
  them matching one event are two runs by design.
- **They are read from the head of `main`**, not from a commit — an event names no push, so the
  platform's one tracked branch supplies the ref. The run records the head sha it built.
- **Which repositories an event is evaluated against**: the union of what the git host lists (`GET
  <qits.ci.git-host-url>/git` → `{"repositories": [...]}`) and what qits-ci already knows — the
  repositories it has runs for. So a repository seeded straight onto the
  git host is a candidate before its first push, which is what makes bootstrapping by hand ("Triggering
  one by hand") work at all. If the listing cannot be read, that is one WARN naming the url and the
  known set answers alone: an unreachable git host never shrinks the candidate list and never fails
  an evaluation.
- **The two trigger types never blur.** A `ci-post-receive.yml` containing `event:`, `when:` or
  `artifacts:` is a config error, and a `ci-event-*.yml` without `event:` is one too.
- `steps:` is the same schema, `docker: true` and `timeout-seconds:` included, with the same
  meanings and the same warnings. The one key it subtracts is **`branches:`**, which is a parse
  error here — see "Binding a step to branches" for why refusing it beats ignoring it.
- The one key it **adds** is **`artifacts:`**, optional, which turns the file into a *release
  pipeline* — see below. It is a parse error in `ci-post-receive.yml`.

### The selection

`when:` is a **list of match groups**, and groups are **OR**'d. A group is a **map of dot-path to
matcher**, and its entries are **AND**'d. So one group with two entries is "x and y", two groups are
"x or y", and there is no third nesting form to choose between. A map value may be a **list** of
matchers, also AND'd — the one thing a plain map cannot spell is two matchers on the same path.

```yaml
when:
  - repoId: { exact: qits-spa-ui-components }     # x AND y
    branch: { exact: main }
  - repoId:                                       # …OR: two matchers on one path
      - { prefix: qits-spa- }
      - { exists: true }
    imageDigest: { exists: false }
```

The whole matcher vocabulary is **`exact`**, **`prefix`** and **`exists`** (a boolean). There is no
`regex`, deliberately: `exact` and `prefix` cover the release train, and a regex invites the
complexity a data-only document exists to avoid. Add one when a real trigger needs one.

**Paths are dot-paths into the payload JSON** — navigation only, no wildcards, no filters, no
indexing (`repoId`, `repository.url`; `tags.0` is refused rather than resolved). A missing path is
`exists: false` and fails `exact`/`prefix`. **Values compare as strings**, so a non-string JSON
value compares by its literal: `count: { exact: "3" }` matches the number 3 and `green: { exact:
"true" }` matches the boolean. Quote them — bare `yes` is a YAML *boolean* and `exact: yes` is a
parse error rather than a comparison against `"yes"`.

**An absent or empty `when:` means unconditional**: the trigger fires on every event of the name it
declared. That is the documented default, and it is why this file is **strict about unknown
top-level keys** where `ci-post-receive.yml` is lenient about them — a mistyped `wehn:` would
otherwise parse as "no selection", and no selection means *every* event. Unknown matcher keys,
non-string match values, malformed structure and duplicate keys are all parse errors too, logged at
WARN naming the repository and the file. **One unparseable trigger file never disables the
repository's others.**

> **A `when:` that matches an event your own build publishes is an unbounded build loop.** A green
> run publishes `BuildSuccessful`; a trigger in the same repository selecting that event runs a
> build, which publishes another `BuildSuccessful`, and so on forever. Each hop is a *new* event id,
> so neither the durable claim nor the run-row dedupe engages — they stop replays, not descendants.
> A `SoftwareRelease`
> trigger has the same shape and one extra trap: an `exact:` on the upstream's repo id is the whole
> defense, and widening it to `prefix: qits-spa-` would close the circle by matching the repository's
> own releases.
>
> **A release pipeline selecting its own repository is the exception, and it is safe for a reason
> worth knowing.** It triggers on `SCMRelease` and publishes `SoftwareRelease` — two names, so the
> circle does not close. Widen that `when:` to the event it publishes and it does.
>
> **The second shape needs no bus at all, and it is new with `branches:`.** A step bound to `prefix:
> maintenance/` whose script force-pushes a `maintenance/*` ref re-triggers its own pipeline through
> post-receive — a loop with no event, no trigger file and no dedupe anywhere near it. The release
> train's own step cannot: it pushes only through qits-workspaces' release door, which targets
> `main`.
>
> **Review is the only guard for both.** Cycle and self-reference detection is a separate future
> feature that builds the graph of trigger declarations across repositories and finds cycles there,
> with its own UX; nothing in this feature guesses at it. The provenance columns a triggered run
> records are the trail it will consume.

### The release pipeline, and what it declares

A **release pipeline** is an ordinary event trigger with two things added: it selects its own
repository's `SCMRelease` — qits-workspaces publishes that the moment a release push is accepted —
and it declares the artifacts it publishes.

```yaml
# .config/qits/ci-event-release.yml
event: SCMRelease
when:
  - repository: { exact: qits-spa-ui-components }   # its OWN id, exact — see the loop warning
artifacts:
  - { type: npm, name: "@qits/ui-components" }
  - { type: maven, name: "eu.wohlben.qits:qits-eventstream" }
  - { type: docker, name: qits/qits-stt }
steps:
  - image: qits/build-images/node-base:latest
    script: |
      v="$(printf '%s' "$QITS_EVENT_PAYLOAD" | jq -r .version)"
      git fetch origin "refs/tags/$v:refs/tags/$v"
      git checkout --detach "$v"
      npm ci && npm publish --tag latest
```

**Checking out the released tag needs no platform change.** The two `git` lines above are measured
working inside a step container: the daemon's clone has the remote, a fetch of one tag refspec is
cheap, and `checkout --detach` lands on the peeled commit even for an annotated tag. qits-ci's
triggering surface is unchanged — a tag push is not a CI trigger and deliberately never became one.

`artifacts:` is a **non-empty list of mappings**, each exactly `{type, name}`:

- **`type`** is `npm`, `maven`, `docker` or `daemon`, and nothing else. The keyword is also the value
  on the wire. `daemon` names a **platform daemon binary** — `qits-ci-daemon` and its kind:
  executables qits-artifacts holds and the platform downloads and runs, rather than packages a
  third-party tool installs. It is a type so the release train can announce the one binary every CI
  run depends on; the PUT that publishes it is a step in that repository's own pipeline, exactly as
  `npm publish` is.
- **`name`** is the **exact package name**, non-blank. A scoped npm name has to be quoted — `@` is
  a reserved YAML indicator, so `name: "@qits/ui-components"`. A docker name is **unqualified**
  (`qits/qits-stt`, no registry host): the registry is `qits-artifacts:8080` inside a step container
  and `registry.dev.localhost:8080` to qits-ci and qits-cd, so no qualified reference is portable
  and the consumer is the one that knows which address it stands at.
  A Maven name is an unqualified `groupId:artifactId` GAV prefix; the event's `version` supplies
  the third coordinate and the consumer supplies the repository URL.
- Everything about it is strict, the way the rest of this file is: an empty list, an unknown type, a
  missing or blank name, an extra key in the mapping, or a wrong shape is a parse error naming the
  file. Omitting the key entirely is how a pipeline says it publishes nothing.

**What it declares is not what it observed.** qits-ci never learns how to publish anything, so it
cannot see what a step pushed; the declaration is a claim, and a green pipeline that quietly skipped
its publish announces an artifact that is not there. That is the accepted price, and it buys the
thing an emitted report could not: the declarations are **statically readable**, so a cross-repo
dependency graph can be built from the trigger files without running a single pipeline.

### `SoftwareRelease`, and why it is the one to trigger on

When a run whose trigger file declared artifacts goes **green**, qits-ci publishes **one
`SoftwareRelease` per declared artifact**:

| Field | What |
|---|---|
| `repository` | the repository whose pipeline published it — this repo, not the upstream |
| `version` | read out of the **triggering** event's payload: `version` on an `SCMRelease`, `tagName` on an `SCMPublishTag` (a release stamp IS the name of the tag the release push created) |
| `packageType` | `npm`, `maven` or `docker`, as declared |
| `packageName` | the declared name, verbatim |

Each one carries the triggering event as its `parentId`, so N artifacts are N siblings under one
cause and the whole train is a chain in the event log.

It means **the package is in qits-artifacts and you can install it** — which is why a downstream
bump pipeline triggers on this rather than on the SCM release. `SCMRelease` fires when source
control has the version; the artifact does not exist until the upstream release pipeline has built
and published it, and the gap between the two is an entire build.

Three consequences worth stating plainly:

- **`BuildSuccessful` is untouched.** Every green run still announces itself exactly as before.
  `SoftwareRelease` is additional, never a replacement.
- **A repository with no release pipeline publishes nothing, and the train stops there.** That is
  not a failure mode, it is the design: an event nothing declares a trigger for is a `continue`.
- **A declaration whose trigger carries no version publishes nothing**, with a WARN naming the run
  and the event. The version belongs to the release the pipeline built and qits-ci will not invent
  one; a file declaring artifacts against an event that carries neither `version` nor `tagName` was
  written for a trigger that cannot feed it.
- **And a green pipeline is only half of it.** The announcement also needs an `SCMRelease` for the
  same `(repository, version)` — see the next section.

### The release join: a `SoftwareRelease` needs a real release

A green release pipeline is **not enough** to announce. qits-ci announces a `SoftwareRelease` for a
`(repository, version)` only when both of these exist, in either order:

1. a **green release-pipeline run** for that pair, and
2. an **`SCMRelease`** for that pair — the event only qits-workspaces publishes, and only for a real
   release.

The reason is the bootstrap. A rebootstrap restores each repository's release **tag** so the platform
re-derives its artifacts; that is a restore, not a release, and it produces `SCMPublishTag` alone.
Announcing off the tag made every replay impersonate a release: the train woke, every consumer ran a
bump, and each bump ended in a release call against a qits-workspaces the boot had not deployed yet.
A replay has no novelty to announce, and `SCMRelease` is exactly the platform's word for novelty.

Both halves are rows, so the two may arrive in any order and a restart between them costs nothing:

- an `SCMRelease`-triggered run carries both facts on one row and announces at green, with no lookup
  — which is also what keeps a hand-supplied event through `POST /ci/api/events/trigger` working;
- a tag-triggered run that finds the release already recorded announces at green;
- a tag-triggered run that finishes first leaves the announcement **owed**, and the `SCMRelease`
  makes it when it arrives;
- a tag-triggered run whose release never comes never announces. **No timeout and no fallback.**

The owed announcements live in `ci_release_announcement` (`announced_at` null is "still owed"), the
release facts in `ci_scm_release`, and `service/…/bus/ScmReleaseListener` is the durable consumer
that records them (`consumerId` `ci-release-facts`). A boot sweep pairs anything a crash left owed.
An announcement is published before its row is marked, so the failure this favours is announcing
twice rather than not at all.

The event's name and its three payload fields are strings on this side — qits-workspaces publishes
no vocabulary jar — and `ScmReleaseContractTest` is what keeps them honest: a transcription of that
record, named against its source file, serialized by the real `CanonicalJson` and driven through the
real listener. Rename a field there and it is renamed here in the same campaign.

### What an event-triggered step container gets

The four `QITS_EVENT_*` variables, alongside everything every step container already receives:

| Variable | What |
|---|---|
| `QITS_EVENT_ID` | The event that caused this run — also its own events' `parentId` |
| `QITS_EVENT_NAME` | The event's name, the same one `event:` matched |
| `QITS_EVENT_OCCURRED_AT` | The event's own timestamp, ISO-8601 |
| `QITS_EVENT_PAYLOAD` | The canonical JSON payload, **verbatim** |

The payload is not flattened into per-field variables: env names derived from payload paths invite
collisions and quoting bugs, and `jq` — which the step images carry — is already the platform's
answer inside a step. A push-triggered run gets none of these.

### Which repositories are asked

Every arriving event is evaluated against the union of **the platform's repository catalogue** and
**what qits-ci already knows** — its recorded runs' repo ids. The catalogue is what lets a repository
that has never pushed event-trigger at all; the known set is what still answers when the catalogue
cannot be read, because a read failure must never shrink the candidate set. It is one method
(`CiCandidateRepos`), which is how the catalogue was swapped: one class, nothing in the engine moved.

**Which catalogue is a kill switch.** With `qits.ci.projects-url` set, it is qits-projects' `GET
/projects/api/repositories` — the only listing that answers the public `(projectId, name)` pair, and
after the identity cutover the only one qits-ci may read at all, since the git host's own `GET /git`
becomes an internal storage listing of UUIDs. With the key unset it is that git-host listing, exactly
as it was before this campaign. A catalogue entry with no `name` is skipped: no public address means
no content route to read its trigger files from.

The candidate unit is `(repoId, projectId, name)`, and the pair is what the trigger read is addressed
by. A candidate qits-ci knows only from its own run rows carries no pair and is read id-addressed.

### The third file: `.config/qits/ci-platform-event-*.yml`

One repository carries pipelines for **every** repository. `qits.ci.platform-pipelines-repository`
names it — `qits-qits`, the wrapper, by default — and its
`.config/qits/ci-platform-event-*.yml` files are read at its `main` head and evaluated against every
arriving event, on top of each candidate's own trigger files.

The file format is the ordinary trigger format: same `event:`, same `when:`, same `steps:`, same
strictness, same `CONFIG_ERROR` handling. **What differs is which repository the run is about.** A
platform pipeline records its run against — and its steps clone — the repository the event's
**payload** names in a `repository` field. So one file bumps the whole catalogue instead of one file
per repository per dependency.

```yaml
# in qits-qits, at .config/qits/ci-platform-event-maintenance-bump.yml
event: MaintenanceBump
steps:
  - image: qits/build-images/maven-base:latest
    script: |
      repository=$(printf '%s' "$QITS_EVENT_PAYLOAD" | jq -er .repository)
      ...
```

Three ways it records nothing, each one WARN naming the event and the repository: the payload carries
no `repository`, it names one the catalogue does not hold, or that repository could not be read for
this evaluation so there is no head to record a run at. A read failure is not a run.

**Both kinds of file fire.** A repository with a local `ci-event-*.yml` and a platform
`ci-platform-event-*.yml` selecting the same event gets **two runs** — two files, two declared
pipelines — because the dedupe is per `(event, repository, config path)` and the paths differ. The
run row records which file declared it, and the `ci-platform-event-` prefix is what tells the two
apart wherever a run is read back. The platform repository's own `ci-event-*.yml` files are
unaffected and still build that repository.

The cost is **one** listing of that one repository per arriving event and nothing per candidate: the
head a platform run is recorded at is the one the candidate pass already resolved. A blank
`qits.ci.platform-pipelines-repository` turns the feature off and reads nothing at all.

### Exactly one run per (event, trigger file)

However many groups of a `when:` match, matching is boolean rather than multiplicative. The
guarantee that survives a redelivery and a race is a **database unique constraint** on
`(trigger_event_id, repo_id, config_path)`: a second arrival of the same event — bus replays are
legal and catch-up redelivers on purpose — is dropped as already-triggered and records no second run.
It covers pushes too, since a push run is named by the `SCMPublishCommit` that announced it: one
announced push is one run. Every run records why it exists (`triggerType`, `triggerEventId`,
`triggerEventName`, `configPath` on `GET /ci/api/runs`), and its own
`BuildSuccessful` carries that event as its `parentId`, so a release train is a chain in
the event log rather than a set of rows distinguishable from coincidence only by their timestamps.

### One push of many tags: the newest tag, once

A trigger file may declare `event: SCMPublishTag`, and one push can write any number of tag refs.
The git host announces **every one of them** — a tag is a fact about the repository and qits-projects'
backup consumer needs them all — so a five-tag release push would otherwise be five runs of one
pipeline, four of them building a version nobody asked for.

qits-ci collapses them instead. When a tag-triggered run is accepted and another **queued** run
exists for the same repository and the same trigger file, the one with the **lower tag by version
sort** is marked `DEDUPED` — the same columns the per-branch push supersede writes
(`status FAILED`, `cancellationReason DEDUPED`, `supersededByRunId`), and it may be the run that was
just accepted, since a fan-out arrives in no order. A push of N tags therefore leaves one run to do.

- **Version sort, not string order.** Digit runs compare as numbers, so `2026.810.184518` is newer
  than `2026.810.98` — which plain string order gets backwards. The rules are in `VersionSort`, and
  it is hand-rolled rather than a dependency for the reason every client here is.
- **Only queued runs are touched.** A lower tag whose run has already started keeps running:
  cancelling a build to save the time it has already spent is the worse trade. What is guaranteed is
  that a multi-tag push converges, not that it never starts a second container.
- **Every announced tag still has a row**, so the record says what was announced and what became of
  it. Only one of them is `QUEUED`. A row's `supersededByRunId` names what beat it *at the time*, so
  tags arriving out of order leave a chain of them ending at the run that stands.
- **A tag that cannot be read out of a payload supersedes nothing**, because a failure to compare is
  not a lower version. **A tag equal to a queued one does** supersede it — that is a tag that moved,
  and the later announcement is the current one, which is the rule a second push to a branch gets.
- **Nothing but tags.** No other event on this bus carries a field that orders, so two
  `BuildSuccessful` events are two independent reasons to build and stay that way.

### Triggering one by hand

The bus is the primary trigger and stays that way. `POST /ci/api/events/trigger` is the second way
in: it runs the same evaluation against an event the **caller** supplies, for any domain-event
pipeline type. It is for the two things the bus cannot do — reruns ("run that release train again")
and bootstrapping ("this platform was seeded without ever publishing the events its pipelines wait
for").

```
POST /ci/api/events/trigger
{
  "name": "SoftwareRelease",
  "payload": {
    "repository": "qits-spa-ui-components",
    "version": "1.4.0",
    "packageType": "npm",
    "packageName": "@qits/ui-components"
  },
  "occurredAt": "2026-08-04T09:00:00Z",
  "eventId": "1b6f0d2a-4f0e-4b8a-9a12-2a3e6f8c0d11"
}

200 OK
{
  "eventId": "1b6f0d2a-4f0e-4b8a-9a12-2a3e6f8c0d11",
  "runIds": ["3f0c…"],
  "repositoriesRead": 8,
  "repositoriesSkipped": []
}
```

`name` and `payload` are required; `payload` must be a JSON object and is passed through as sent,
never bound to a type. `occurredAt` defaults to now and lands on the run row as the event snapshot's
timestamp, exactly as a bus arrival's does. A blank name, a missing payload, or an unparseable
`occurredAt` or `eventId` is a 400.

**It evaluates before it answers, and that is the guarantee.**

- **200** — the evaluation happened. Every id in `runIds` is a run row that exists now, findable by
  `triggerEventId` on `GET /ci/api/runs?repositoryId=…`. An empty `runIds` with an empty
  `repositoriesSkipped` means the event was offered to every candidate repository and matched none.
- **503** — it did not happen: no candidate repository could be read, or the evaluation threw.
  `Retry-After` says when to come back. Nothing was lost that a retry cannot recover, because
  nothing was accepted.

`repositoriesSkipped` names the candidates that did not answer — the git host did not reply, the
repository is gone, it has no `main`, or `qits.ci.trigger-deadline-seconds` arrived before its turn.
A skipped repository is not a repository that said no, and the two are reported apart so that
"nothing matched" is never a lie about an unreachable git host.

**This replaced a 202, and the replacement is the fix for a measured loss.** The call used to hand
the event to `ci-trigger-worker`'s bounded queue and answer "accepted" whatever came back. A bus
frame can afford that: unevaluated, it stays owed and the next catch-up sweep offers it again. A
caller-supplied event rides no bus, is on no log and holds no claim, so nothing anywhere will ever
offer it a second time — for this endpoint, "queued" and "lost" were the same answer. On 2026-08-10 a
bootstrap's release replay was answered 2xx for an event that was never evaluated: no run, and no
line at any level for thirty minutes. An event nobody can redeliver must be evaluated by the process
that accepts it, or refused.

The cost is that the call is as long as the evaluation — one git-host read per candidate repository
— and that a manual trigger fans out beside the trigger worker rather than behind it. That is the
right way round: the single worker exists to keep a burst of *machine* events from storming the git
host, and this is one request from one person.

**The id is the whole contract, and both of its behaviours are wanted.** The dedupe above is a
constraint on `(trigger_event_id, repo_id, config_path)`, so:

- **Omit `eventId`** and a fresh random UUID is minted. Nothing collides, so the same payload reruns
  as often as you ask. This is the default because a rerun is what a person wants.
- **Pass `eventId`** and you opt into the dedupe. The call is then idempotent — a second one records
  no run — which is what makes it safe in a bootstrap script that may run twice. Pass the *original*
  event's id to say "only if this never triggered". It is also what makes a **retry after a 503**
  free: an evaluation that reached some repositories before it gave up leaves rows the retry will
  not duplicate.

Nothing else about a triggered run changes: it reads the trigger files from the head of `main`, the
step containers get the same four `QITS_EVENT_*` variables, and a green release pipeline announces
its `SoftwareRelease`s under the supplied id as their `parentId`. So a hand-supplied event is a real
event as far as everything downstream is concerned — including the loop warning above.

## How a step runs — qits-ci starts containers, and that is all

> **No code path in qits-ci runs repo-controlled code as a host process, and none runs it through
> `docker exec`.** A step's script reaches a container only as the reply on the socket that
> container's own daemon dialled, and executes only as that daemon's child inside the sandbox.

One container per step, launched from the step's declared image, in sequence — only a step's
completion starts the next one, and no state crosses steps. Per step:

1. **Launch.** qits-ci mints an id and a secret, then asks qits-containers to put a container at
   `PUT /containers/api/containers/<owner>/ci-step/<container name>` with the image's entrypoint
   overridden to a fixed, host-authored bootstrap: fetch the daemon binary from
   `$QITS_CI_DAEMON_BINARY_URL`, `chmod +x`, `exec`. Nothing about the repository is interpolated
   into that text — the whole contract rides as environment. The image contract is therefore `git`,
   `bash`, and a downloader (`wget` **or** `curl`).
2. **Register.** The daemon **dials out** to `ws://…/ci/daemon` presenting its id and secret.
   qits-ci never dials in and never learns an address from a container.
3. **Initialize.** The daemon does its own shallow clone and checks out the pushed sha, then says so
   — or reports a structured failure. A checkout that cannot find the commit is how a force-push
   between the host's ancestor check and the container's clone surfaces, and it makes qits-ci
   re-read the config source and discard a run that describes a push which no longer exists.
4. **The step arrives as the answer.** The host replies to `Initialized` with this step's script.
   The container never receives the config file, only its own script.
5. **Execute and stream.** The daemon runs the script in the checkout and streams stdout/stderr back
   as chunks, then a terminal frame with the exit code and whether its own deadline killed the
   child. Timestamps are stamped **here**, on receipt, never taken from the container.
6. **Persist at finish.** Chunks feed a bounded in-memory relay that `GET /ci/api/runs/{runId}`
   exposes as `live` while the run is running; the step's **row is written once, already terminal**,
   at the step's end. The database never holds a half-written step.
7. **Teardown.** The container is deleted on every path, its secret is forgotten, and the next step
   starts — or the run closes and the remaining steps are recorded `SKIPPED`. A path that needs the
   container's own log asks for it **on the delete**, so the read-before-removal ordering cannot be
   lost; the delete is idempotent, so the unconditional one that follows costs nothing.

The same seven steps as a diagram, plus the one thing prose keeps having to disambiguate — the
control WebSocket every step dials versus the host's docker socket only a `docker: true` step is
given — are in [`docs/step-execution-flow.md`](docs/step-execution-flow.md). The prose above stays
the contract; the diagram illustrates it.

**qits-ci spawns no process at all** — the config read is an HTTP call, so the host needs no `git`,
and the container lifecycle is an HTTP call too, so it needs no docker CLI and no socket. What used
to be `run`, `logs`, `rm`, `ps` and `network inspect`/`create` is four requests to qits-containers:
one `ensure`, one delete that brings the log back, one plain delete, and one scoped destroy-all at
boot. `exec` was never in the vocabulary and is not on the wire either, not even to deliver the
daemon binary.

A step's script is **repo-controlled code**, so the step container is a hostile-code sandbox:
`--cap-drop=ALL`, `no-new-privileges`, no docker socket unless the step declared `docker: true`
above, and memory/pids/cpu caps
(`qits.ci.memory-limit`, `…pids-limit`, `…cpus`). The daemon lives *inside* that sandbox and the
script is its child, so everything arriving over the socket is attacker-influenced data about the
run: recorded, never trusted. The residual gap — a push is itself unauthenticated, and running
repo-committed scripts is the feature — is a known, documented issue.

**The daemon is pinned per run, off a ladder qits-ci keeps for itself**
(`ci-daemon-autoadopt-plan.md`). `CiDaemonPins.answer()` is resolved once when a run is created,
recorded on the run row, and injected into every one of that run's containers, so a deploy landing
mid-run cannot make step 3 speak a different protocol than step 1. The top rung is the newest
**adopted** daemon release that has proven itself; the bottom rung is the deployment's own
`qits.ci.daemon-version`, which is never demoted. A release is adopted off the `SoftwareRelease` bus
event qits-ci-daemon's own release pipeline publishes, then proven by launching it in a real
container and checking it dials and speaks the host's protocol version — a release that never dials,
or dials with a protocol version this host does not know, is rejected and the ladder falls to the
rung below. `qits.ci.daemon-autoadopt-enabled` (default `true`, dark under `%dev`/`%test`) turns
adoption off entirely, leaving the configured pin the only rung. The download address is
version-addressed — `qits.ci.daemon-binary-url-template` with `{version}` resolved through the
ladder — and a version is validated at adoption as a single safe path segment (a calver or a digest
hex, never `/`, `..`, `?`, `#` or whitespace) rather than checked at boot, because a version now
arrives untrusted off the bus instead of only from a reviewed deployment. Deployments not on
`qits-net` under the standard aliases also need `qits.ci.container-daemon-url`. All of this is
documented where it is shipped, in the `ci` jar's `META-INF/microprofile-config.properties`.

**The pin is queryable, and an empty ladder is a readiness check rather than a mystery.** `GET
/ci/api/daemon` answers `{daemonName, daemonVersion, previousDaemonVersion, source}` — the ladder's
top rung, what a run started right now would download and what it would fall back to — which is a
different and much smaller question than what `ci_run.daemon_version` records, since that is history
and the run listing clamps at 100. qits-artifacts' daemon GC reads it when it plans a sweep and
aborts with nothing deleted if it cannot, the same fail-closed shape the docker strategy has against
qits-cd's deployments. When every adopted candidate is rejected and no `qits.ci.daemon-version` is
configured, `daemonVersion` answers blank, `source` answers `"none"`, and `/q/health/ready` goes
DOWN naming the rejected versions — an honest "no pin" state, not a boot failure, so `GET
/ci/api/daemon` stays answerable to say why. Runs still execute and still fail with today's
distinguishable states; nothing about an empty ladder refuses a run.

**Failures stay distinguishable.** The orchestrator refusing the launch (or not answering at all), a
container whose bootstrap never produced a daemon (its own log tail comes back on the very call that
removes it and becomes the step's output), a daemon that registered and then went quiet, a structured setup failure, a lost socket and
a genuine step timeout are six different recorded outcomes — none of them is "the step failed with
exit −1".

## Following a run, and stopping one

`GET /ci/api/runs/{runId}` is the whole live surface: while the run is `RUNNING` it carries a `live`
object — the step index in flight and the bounded tail it has printed. **Poll it.** There is no SSE
and no WebSocket on the read side; the daemon makes live output possible, it does not oblige a push
transport. The relay is memory and dies with the process — the persisted tail on each step row is
the record.

`POST /ci/api/runs/{runId}/cancel` answers 202 and asks the in-flight container to stop. Its optional
JSON body is `{ "reason": "…" }`; absent or blank records `USER_CANCELLED`. The step it
was on is recorded `FAILED` with "cancelled" in its output and the rest `SKIPPED`; the run itself
finishes as `CANCELLED`, not as a failed pipeline verdict. A run still
`QUEUED` can be cancelled too, and it is the cheap case: there is no container to ask, so the run is
recorded `CANCELLED` with no steps and the worker never picks it up. Cancelling a run that has already
finished is a 409. Like every other operation this service serves, it is in `docs/openapi.yml`;
nothing is hidden there any more, since the one hidden operation was the machine-only push intake.
A queued push is also cancelled automatically when a
newer push for the same repository and branch is accepted: it records `DEDUPED` and the newer run's
id, which the run detail links to. Event-triggered runs are excluded because distinct trigger files
on one branch are independent pipelines, not duplicates.

## What a restart costs

**A run is a row from the moment it is accepted.** The push listener and the trigger engine both
`INSERT` before they return, with status `QUEUED`, and the worker flips it to `RUNNING` when it
dequeues it. Before that, a queued run was a closure on a single-threaded executor and nothing else —
invisible to every read surface, and gone with the process. That was the lossy intake: a redeploy
landing between the push and the build lost the build with no row anywhere to say so, and the fix was
to POST the post-receive again by hand. **Both halves of that loss are closed now**, and by different
mechanisms: the row covers a restart between accept and execution, and the durable claim covers a
restart before the push was ever accepted — a push announced while this process was away is read back
off the event log rather than needing a replay.

On boot:

- push-triggered runs left `RUNNING` are marked `FAILED` — their in-flight step died with the
  process and CI cannot assume arbitrary repository-authored work is safe to repeat;
- event-triggered runs left `RUNNING` have partial step rows cleared and restart from their stored
  event/trigger snapshot. That restart is what recovers them: the event was claimed when it arrived,
  so catch-up will not offer it a second time. Event pipelines are an at-least-once boundary and
  their scripts must be idempotent;
- runs left `QUEUED` are **re-enqueued**, oldest first, because they never started and the row says
  everything needed to start them. Nothing is lost and nothing has to be replayed;
- containers carrying the `qits.ci.run` label are removed, and a daemon from a previous life that
  dials in presents a secret this process does not know and is closed 1008.

For an event-triggered run the row includes the original event timestamp, its canonical payload and
the exact trigger-file content that matched. Recovery reparses that snapshot, preserving both the
pipeline and `$QITS_EVENT_*` environment even if `main` moved while CI was down. It asks the event
log for nothing.

No durability is added beyond the row by design — the launch table is still memory, and that is what
keeps the rest of the restart story free.

**So the recording rule is revised, deliberately.** It used to be "a run is only ever recorded when
it says something true about a commit", which was a statement about when the `INSERT` happens. It is
now: *a run row exists from the moment the work is accepted, and it is removed again if it turns out
to describe nothing that happened.* What a finished worker leaves behind is unchanged outcome for
outcome — no config file, a vanished commit and an unreachable git host all still leave **no row** —
the difference is a transient `QUEUED` row in between, which `GET /ci/api/runs/active` and, briefly,
a repository's own listing will show.

## Deploying it

- **qits-ci needs no docker socket.** It holds none, mounts none and spawns no process at all:
  every step container is started, read and removed through **qits-containers**, which is the one
  service on the platform that talks to a docker daemon. Point `qits.containers.url` at it
  (scheme+host+port, no path — it defaults to the qits-net alias) and give this service nothing else
  about docker. A deployment still mounting `/var/run/docker.sock` into qits-ci is giving it
  root on the host for no reason left in the code.
- **Two qits-ci instances may share one docker daemon now, and `qits.ci.containers.owner` is what
  keeps them apart.** The boot reap used to be a host-wide `qits.ci.run` label sweep — it removed
  every labelled container on the daemon, including one another instance was running a step in, so
  deploying the second killed the first's in-flight builds. It is now a request for *this owner's*
  own rows in the orchestrator's registry, and two owners cannot see each other's. The constraint
  that replaces "one instance per daemon" is smaller and is a config fact rather than a property of
  the host: **two instances must not share an owner string.** The default reads
  `quarkus.oidc-client.client-id`, which qits-idp mints per environment (`dev-qits-ci`,
  `prod-qits-ci`), so two environments on one daemon are already apart; two instances of one
  environment sharing capacity are not a supported shape — size a single instance with
  `qits.ci.concurrent-builds` instead.
- Set `qits.ci.concurrent-builds` to the maximum number of pipelines this qits-ci instance may run
  at once (default **4**, minimum **1**). Steps remain sequential within one pipeline. Size this
  together with the host's CPU and memory and the per-container `qits.ci.cpus`/`memory-limit` caps.
- Set `qits.ci.git-host-url` / `qits.ci.container-git-url` to the git host as reachable from the ci
  host and from a step container respectively. **Both end at the service, not at `/git`** — ci
  appends `/git/<repoId>` itself, because `/git` is the codebase's segment for the git host while
  *which* service hosts it is a deployment fact. The git host is **qits-githost**, which serves
  `/git/**` at its root, so the value is scheme+host+port and nothing else —
  `http://qits-githost:8080`, and a read lands on `/git/<repoId>`. It used to be
  `http://qits-artifacts:8080/artifacts` while the host lived inside qits-artifacts; a value that
  keeps that segment 404s every read, and a 404 on the blob reads as "this commit declares no
  pipeline", so every push would report no pipeline instead of an error. The container-side alias
  only resolves on the network ci itself is on, so `qits.ci.network` must be set together with it.
- Leave `qits.auth.machine.audience=qits-ci` alone. It is this service's id at qits-idp — the `aud`
  its tokens carry — not a deployment fact.
- Turn the machine guard on with `QITS_AUTH_MACHINE_REQUIRED=true`, once qits-idp is reachable.
  That one platform-wide gate switches on both the bearer validation and every `MachineAuth` call in
  the code, and it ships **off**: with it off the endpoints behave exactly as they did under network
  trust, no bearer needed. There is no third state. Turning it on with no audience set fails at
  startup rather than accepting tokens addressed elsewhere.
- Grant `project=*` to whatever posts `/ci/api/events/trigger`. That is the only guarded write left,
  and an event names no repository, so a grant naming one is a 403 there. **The git host needs no
  qits-idp client at all any more** — it announces pushes on the bus instead of posting to a guarded
  intake.
- The `/ci/api/events/` exemption on qits-gateway's `PublicPaths` covered the push intake and can go
  with it. The manual trigger is a machine call and is expected to carry a bearer; nothing on that
  prefix needs unauthenticated access.
- Keep the run **read** surface behind the deployment's auth policy. It is not machine-guarded, and
  it returns build logs.
- **Configure nothing for the deployer, and know that this is a change.** A deployment used to point
  `QITS_PLATFORM_DEPLOYMENTS_INTAKE_URL` (`qits.platform.deployments.intake-url`) at
  qits-platform-deployments and hand this service qits-idp client credentials
  (`QUARKUS_OIDC_CLIENT_CLIENT_ENABLED`, `QUARKUS_OIDC_CLIENT_CREDENTIALS_SECRET`) so the POST could
  carry a bearer. **All three are gone, and none of them is aliased** — a deployment still setting
  any of them is setting nothing. A green run now announces itself on the bus and
  qits-platform-deployments subscribes, so what makes a deployment happen is `QITS_EVENTS_URL` and
  `QITS_EVENTSTREAM_ENABLED` below, plus a deployer that is subscribing. The deployer's HTTP intake
  still exists for a manual replay; nothing here calls it.
- Set `qits.artifacts.registry-host` / `qits.artifacts.image-repository` to qits-artifacts' registry
  as reachable **from the docker host** — the daemon on the far side of the mounted socket is what
  resolves that name and performs the push, not this process and not the step's CLI. While the
  registry speaks plain HTTP the daemon also needs it in `insecure-registries`. Same class of fact as
  the socket mount, and now the same socket serves both. qits-cd ships the same two keys and derives
  its pull references from them, so the two services must agree.
- **Configure no push credential at all: it is commissioned per run.** `qits.ci.registry-auth.*` is
  gone, and a deployment still setting it is setting nothing. When
  `QUARKUS_OIDC_CLIENT_CLIENT_ENABLED` is on, qits-ci asks qits-idp — at
  `<QUARKUS_OIDC_CLIENT_AUTH_SERVER_URL>/api/clients`, with its own client id and secret as HTTP
  Basic — for a client per run, and every step declaring `docker: true` gets `$DOCKER_CONFIG` and a
  `config.json` holding
  `{"auths":{"<qits.artifacts.registry-host>":{"auth":"<base64 id:secret>"}}}` — which is all the
  docker CLI needs to answer the edge's Bearer challenge on its own — plus the pair itself as
  `$QITS_COMMISSIONED_CLIENT_ID`/`$QITS_COMMISSIONED_CLIENT_SECRET`. With the oidc client off, the
  behaviour that shipped before: no file, no variable, an anonymous push. Reads stay anonymous
  either way, so a deployment pulling from that registry configures nothing. **What the idp must
  grant that client is push rights on the registry and nothing else** — it is readable by the step's
  own script, which runs repo-authored code, and it is worth one pipeline rather than every future
  one. The leftovers of a killed process are reaped at boot and hourly
  (`qits.ci.commission.reconcile-interval`); a commission that could not be made fails the step
  after `qits.ci.commission.patience`.
- **Set `qits.ci.docker-auth-hosts` to every registry host a step build touches, if that is more
  than one.** The docker client sends a login per hostname, so the `config.json` needs an entry per
  host — the push registry *and* whatever a step image is pulled from. The default is
  `qits.artifacts.registry-host` alone, which is right for a deployment that pulls and pushes at one
  address; behind the edge it is
  `QITS_CI_DOCKER_AUTH_HOSTS=registry.dev.localhost:8080,mirror.dev.localhost:8080`. All entries
  share the run's one commissioned pair.
- Leave `qits.artifacts.npm.hosted-url` / `qits.artifacts.npm.proxy-url` and
  `qits.artifacts.maven.registry-url` alone on a deployment where
  qits-artifacts answers to its usual alias: they are reached **from a step container**, on
  `qits.ci.network`, so the shipped defaults are already the right values and the host-published
  address used for `registry-host` is exactly the wrong one to copy here. Override them only when
  qits-artifacts moves off the alias.
- Leave `qits.ci.workspaces-url` alone for the same reason: it is qits-workspaces' root as reached
  **from a step container**, and the shipped `http://qits-workspaces:8080` is already right on
  qits-net. Scheme, host and port, **no path** — a step appends `/workspaces/api/…` itself.
- **Give the process its two databases, and the deployment spec is how.**
  `.config/qits/deployments.yml` declares

      resources: postgresql:db, postgresql:eventstream:qits_ci_eventstream

  and qits-platform-deployments creates a role and a database per entry on the tier's postgres
  before the container starts, then injects `QITS_RESOURCE_DB_URL` / `_USERNAME` / `_PASSWORD` and
  `QITS_RESOURCE_EVENTSTREAM_URL` / `_USERNAME` / `_PASSWORD`. The two jars read those variables in
  their own shipped defaults — the `ci` jar the first triple, the qits-eventstream jar the second —
  so a deployment sets no datasource key of its own. **The resource names are load-bearing**: the
  variable names follow them, and renaming either in that file silently stops matching the jar that
  reads it. There is no default behind any of the six, so an unset one leaves the expression
  unresolvable and the process refuses to boot at Flyway rather than opening a store nobody meant.
- Point `QITS_EVENTS_URL` (`qits.events.url`) at qits-events — scheme, host and port, **no path**:
  the client appends `/events/api/events/{id}` and `/events/stream` itself, and a path here yields a
  doubled one and a 404 nothing retries out of. The shipped default is the qits-net alias
  `http://qits-events:8080`, which is already right for a deployment on that network and wrong for a
  host-run process. `qits.eventstream.enabled=false` turns the whole thing off, which is what a
  deployment with no qits-events wants — the keys and their defaults are the qits-eventstream
  jar's, not this module's.
- The outbox is the second of those two databases, with its own Flyway lineage, holding exactly the
  events a publish could not deliver — empty in a healthy process. It used to be a single-writer H2
  under `${user.home}`, and that default cost a rollout: in a container with no `HOME` the native
  binary resolves `user.home` to `?`, H2 refuses a path implicitly relative to the working
  directory, and the process failed to boot at Flyway before anything served. What replaced it
  cannot fail that way, because there is nothing left to default to.

## What is deliberately *not* here

The git host and everything it publishes (qits-githost), the repository and workspace contexts, and
anything to do with running a pipeline inside a workspace container. Pipelines run in their own
throwaway containers; a workspace is never involved.

Retries, a non-advisory gate, and clone/dependency caching across the per-step containers are
follow-ups, not omissions of the extraction. Per-step timeouts are not: a step may declare
`timeout-seconds:` in `.config/qits/ci-post-receive.yml`, and one that declares none gets
`qits.ci.step-timeout-seconds`.
