# qits-ci

The **in-repo CI pipeline**: a repository opts in by committing
`.config/qits/ci-post-receive.yml`; when a push lands, ci reads that config back out of the pushed
commit, runs each step's script in a fresh container of the step's declared image, and records a
per-step pass/fail for the push — advisory, queryable over REST.

    git submodule update --init   # the Angular client at service/src/main/webui
    mvn verify                    # green from a clone alone — no monorepo, no docker, no credentials

## Layout

| Module | What |
|---|---|
| `ci/` | `eu.wohlben.qits.ci.*` — entity, persistence, dto, mapper, control, error. The pipeline itself. No web, no JAX-RS. |
| `service/` | `eu.wohlben.qits.ci.api` — the JAX-RS event intake, the run read surface, the token filter and the exception mapper — plus `…ci.daemonhost`, the step-container control plane (below). |
| `ci-daemon-protocol/` | `eu.wohlben.qits.cidaemon.protocol` — the ci-daemon wire contract, **vendored** from [qits-ci-daemon](https://github.com/QuicklyIterateTheSoftware/qits-ci-daemon) and never edited here. Framework-free; `diff -r` is the drift detector. |
| `eventsourcing/` | `eu.wohlben.qits.eventsourcing` — the platform's **event bus client** (publish to qits-events, listen for what it broadcasts). A library that has not moved out yet: it knows nothing about CI and may not import `eu.wohlben.qits.ci.*` at all. |
| `ci-events/` | `eu.wohlben.qits.ci.events` — the events this service announces, today just `BuildSuccessful`. Depends on `eventsourcing/` and nothing else, so a future consumer takes the vocabulary without taking qits-ci. |

`ci/` is a library jar. **`service/` is the application** — it carries
`<packaging>quarkus</packaging>` and produces a process, as a JVM fast-jar or as a native binary:

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar   # :8080, intake on /ci/api/events/post-receive

    ./mvnw package -Dnative
    ./service/target/qits-ci                              # same routes, ~0.2s to listening

**Native is the shipping form.** `.sdkmanrc` names a GraalVM (`25.0.2-graalce`), so `sdk env` alone
is enough toolchain: the build wants a `native-image` on `GRAALVM_HOME`, `JAVA_HOME` or `PATH`, and
finding none it does not fail — it falls back to pulling a 1.8 GB Mandrel image and compiling under
docker. That fallback still works and is what a GraalVM-less CI gets; it is just not the intended
path, and it is worth recognising by name when a build that normally takes two minutes starts
downloading a container image. Note the coincidence: this service *runs* docker, per step, by
design (below) — the **build** must not touch it.

Most of the 0.2s is opening the H2 file and running Flyway; the framework itself is up in
milliseconds. That is the point of packaging it this way — a restart is a non-event rather than a
window in which pushes arrive and record nothing.

It was extracted as a library, on the assumption that a consuming Quarkus application would pull it
in and gain the routes. That application was never written and under the gateway topology never will
be. `ci` owns its **own datasource, persistence unit and Flyway lineage** (`db/ci/migration`, a
separate H2 under `~/.qits/data/ci`), which is what makes this a standalone deployable rather than a
checkout of the monorepo. The directory names are `ci/` and `service/` because the extracted git history is
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
| in | `POST /ci/api/events/post-receive` — `{repoId, branch, oldSha, newSha}`, one per updated branch ref | guarded by `qits.ci.token` (`X-CI-Token`) |
| in | `GET /ci/api/runs?repositoryId={repoId}`, `GET /ci/api/runs/{runId}` | not token-guarded; they carry build logs, so a deployment must keep them behind its auth policy |
| in | `POST /ci/api/runs/{runId}/cancel` → 202, 409 on a run that is not running | same: no token, behind the deployment's auth policy |
| in | `ws://…/ci/daemon` — the socket each step container's daemon dials **out** to | authenticated by a host-minted per-container secret, not by any token |
| out | where the git host answers, for ci's **own** `git fetch` of the pushed ref — ci appends `/git/<repoId>` | `qits.ci.git-host-url` |
| out | the same, as reachable **from a step container** on the shared network | `qits.ci.container-git-url` |
| out | where a step container downloads the daemon binary from | `qits.ci.daemon-binary-url-template` + `qits.ci.daemon-version` |
| out | `POST /cd/api/events/build-succeeded` — `{runId, repoId, branch, commitSha}`, one per **green** run (the `CdNotifier` seam) | `qits.cd.intake-url`; no token — cd's intake is not gateway-allowlisted, the call stays on qits-net |
| out | `PUT /events/api/events/{uuid}` — one `BuildSuccessful` per **green** run, idempotent (the `RunAnnouncer` seam) | `qits.events.url`, `qits.eventsourcing.enabled` |
| out | `ws://…/events/stream` — dialled out and held open, carrying what qits-events broadcasts back | the same two keys; the address is derived, never configured twice |
| out | the registry a publishing step pushes to, as `$QITS_REGISTRY` and `$QITS_IMAGE_REPOSITORY` in **every** step container — dialled by the *host's docker daemon*, never by this process | `qits.artifacts.registry-host`, `qits.artifacts.image-repository` |
| out | the npm registry roots, as `$QITS_NPM_REGISTRY_URL` (hosted, `@qits/*` publishes) and `$QITS_NPM_PROXY_URL` (the npmjs pull-through cache) in **every** step container — dialled by the *step container itself* on the shared network | `qits.artifacts.npm.hosted-url`, `qits.artifacts.npm.proxy-url` |

The run listing takes the repository as a **query filter, not a path segment**. ci does not own
repositories, so `/repositories/{repoId}/runs` asserted a containment this context does not have —
and put three services under one gateway prefix. `runs` is the entity; `{runId}` stays in the path
because there it is identity rather than scope.

The event sender is the git host's post-receive hook, which lives in
[qits-artifacts](https://github.com/QuicklyIterateTheSoftware/qits-artifacts) (`CiPostReceiveNotifier`).
It was already an HTTP call while ci ran in-process, so the split moved files, not the contract —
only `qits.ci.intake-url` on the sending side changes. That call is **fire-and-forget**: it swallows
delivery failures at debug, so if the two ends disagree about the intake path nothing errors
anywhere and CI simply never runs. Both ends are pinned to `/ci/api/events/post-receive`.

The same arrangement repeats one hop down: a green run is announced to
[qits-cd](https://github.com/QuicklyIterateTheSoftware/qits-cd)'s
`/cd/api/events/build-succeeded` by `service/…/notify/CdBuildNotifier` behind the `CdNotifier` seam
in `ci/control` — fire-and-forget with the same silence hazard, so both ends pin that literal too.
Only `SUCCESS` announces (a red run, a `CONFIG_ERROR` and a discarded run deploy nothing), and a
deployment without a qits-cd is a supported configuration that costs one debug line per green run.

**A green run is also announced to nobody in particular.** The same transition publishes a
`BuildSuccessful` event to [qits-events](https://github.com/QuicklyIterateTheSoftware/qits-events),
through a second seam in `ci/control` — `RunAnnouncer`, implemented by `service/…/bus/BuildSuccessfulAnnouncer`
— and the two are separate ports on purpose: the cd call is a *request* addressed to one service
that is about to act, this is a *statement* anything on the platform may subscribe to. It is a
`PUT` at a UUID the publisher picks, so a retry is a replay rather than a duplicate; a delivery that
does not land goes to an outbox in this process and is retried on a schedule; and it carries the
run's own `finishedAt` as the event's `occurredAt`, plus `imageDigest` — which qits-ci never has,
since a step publishes an image from inside its own container and answers with an exit code.

qits-ci is also the **first consumer** of the same bus: `service/…/bus/BuildSuccessfulListener`
receives its own announcement back off `/events/stream` and logs it. Nothing hangs off that yet; it
is there because a producer nobody has ever seen consume is a bus with an untested second half.

Consuming has **two seams**. `QitsEventListener<E>` names an event class and gets it deserialized —
the one to reach for. `QitsRawEventListener` names a set of event *names* at runtime, may say `"*"`
for all of them, and gets the frame itself; it exists for consumers whose interest is unknowable at
startup, which is what a trigger reading selections out of other repositories' files is. The
subscribe frame is the union of both, `"*"` collapsing it to `["*"]`, and a frame both want reaches
both — typed first.

**Every event carries a nullable `parentId`** — the event that caused it — so a release train is a
chain in the log rather than a set of rows distinguishable from coincidence only by their
timestamps. It is envelope data, stamped by `QitsEventBus.publish` and never declared by an event
class, and it is filled in from an explicit argument or from `CausationScope`, the ambient
thread-local the dispatcher establishes around each listener call. A `BuildSuccessful` from a push
is a root and carries null; when the trigger engine lands, an event-triggered run's will carry the
event that triggered it. The rules that bite are in AGENTS.md under "The eventsourcing module".

Both halves are **dark in `%dev` and `%test`** (`qits.eventsourcing.enabled`), the same posture the
OTLP exporter takes, and a deployment without a qits-events is a supported configuration in exactly
the way a deployment without a qits-cd is.

ci never touches the bare origins on disk: it keeps its **own** bare cache per repository under
`<qits.ci.data-dir>/repos/<repoId>.git` and fetches into it over the git host's URL. That is what
lets it run on a machine with no shared filesystem with qits.

The fetch asks for the **branch ref**, not the bare sha — an unadvertised-object fetch would mean
relaxing the git host's want policy for every unauthenticated client. ci then verifies the pushed
sha is still an ancestor of the fetched tip: a racing push still runs, a force-pushed-away commit
records nothing rather than a spurious red run.

## The file a repository commits

`.config/qits/ci-post-receive.yml` is a list of steps and nothing else. Everything additive since
has stayed additive over that core:

```yaml
steps:
  - image: qits/build-images/ci-base:latest    # required — the container this step runs in
    script: ./mvnw -B -ntp verify              # required — bash, run in the checkout
  - image: qits/build-images/ci-base:latest
    docker: true                               # optional, default false — see the warning below
    timeout-seconds: 3600                      # optional — else qits.ci.step-timeout-seconds
    script: |
      ref="$QITS_REGISTRY/$QITS_IMAGE_REPOSITORY/qits-gateway:$QITS_CI_SHA"
      docker build -t "$ref" -f docker/Dockerfile .
      docker push "$ref"
      docker rmi "$ref" || true
```

Unknown keys — top level or per step — are never read, so a repo may carry config for a newer
qits-ci. Keys that *are* known and unreadable (`timeout-seconds: soon`, `docker: yes-please`) are a
`CONFIG_ERROR` run instead: a repo that meant to bound a step or to ask for a socket must find out.

**Publishing an image is not a feature here, it is a step.** Steps are sequential, so a push runs
only after the build steps went green; a failed push is a failed step is a **failed run**, so the CD
announcement (`SUCCESS` only) keeps implying the image exists. The tag is the whole contract with
qits-cd, which pulls `<registry>/<repository>/<application>:<sha>` where `<application>` is by
convention the repository's name — the script must spell exactly that, and the only enforcement is
the convention plus cd's `IMAGE_MISSING` telling on a mismatch.

> **`docker: true` makes that step root-equivalent on the host.** It bind-mounts the host's docker
> socket (`qits.ci.docker-socket-path`) into the step's container, and the socket *is* the daemon
> and the daemon is root: such a step can mount host paths, start privileged containers and leave
> the sandbox at will. The `--cap-drop=ALL` / `no-new-privileges` flags stay on and still fence the
> step's own process tree, but they do not bound what the daemon will do on its behalf. It is
> accepted for the POC under the standing posture (the sources are trusted), and it is **opt-in per
> step**: a repository declares it, a config diff shows it, and every step that does not declare it
> keeps exactly the sandbox described below.

The build and the push both happen in the *host's* daemon — the step's CLI is only a client — so the
registry address must resolve and be trusted **from the docker host**, not from this process. The
step image supplies the docker CLI; the platform supplies the socket and the two coordinates.

**Publishing an npm package needs none of that.** `$QITS_NPM_REGISTRY_URL` (hosted — where `@qits/*`
is published) and `$QITS_NPM_PROXY_URL` (the pull-through cache of npmjs every install resolves
through) are injected into every step container alongside the two above, and the caveat on them is
the **opposite** one: they are dialled by the *step container itself* over the shared network, so an
npm publish is an ordinary HTTP step with no socket, no `docker: true` and no root-equivalence. The
consequence for a deployment is that the value which is right for these is the in-network alias — a
host-published mapping substituted for `$QITS_REGISTRY` (the local stack's `localhost:8081`) must
**not** be substituted for these, because a step container has no such address.

A step writes its own `~/.npmrc` from the two, so no repository ever spells a registry address:

```sh
# The token line is npm-CLI ceremony only — the server reads nothing.
cat > ~/.npmrc <<EOF
registry=${QITS_NPM_PROXY_URL}
@qits:registry=${QITS_NPM_REGISTRY_URL}
${QITS_NPM_REGISTRY_URL#http:}:_authToken=qits-ci
EOF
```

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

## How a step runs — qits-ci starts containers, and that is all

> **No code path in qits-ci runs repo-controlled code as a host process, and none runs it through
> `docker exec`.** A step's script reaches a container only as the reply on the socket that
> container's own daemon dialled, and executes only as that daemon's child inside the sandbox.

One container per step, launched from the step's declared image, in sequence — only a step's
completion starts the next one, and no state crosses steps. Per step:

1. **Launch.** qits-ci mints an id and a secret, then `docker run -d` with the image's entrypoint
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
7. **Teardown.** The container is `docker rm -f`'d on every path, its secret is forgotten, and the
   next step starts — or the run closes and the remaining steps are recorded `SKIPPED`.

The same seven steps as a diagram, plus the one thing prose keeps having to disambiguate — the
control WebSocket every step dials versus the host's docker socket only a `docker: true` step gets
mounted — are in [`docs/step-execution-flow.md`](docs/step-execution-flow.md). The prose above stays
the contract; the diagram illustrates it.

`GitConfigFetcher` shells ci's own host `git` against its own bare cache, and that plus the docker
CLI is the entire set of processes qits-ci spawns. Its docker vocabulary is container lifecycle:
`run`, `logs`, `rm`, `ps`, `network inspect`/`create`. `exec` is not in it, not even to deliver the
daemon binary.

A step's script is **repo-controlled code**, so the step container is a hostile-code sandbox:
`--cap-drop=ALL`, `no-new-privileges`, no docker socket unless the step declared `docker: true`
above, and memory/pids/cpu caps
(`qits.ci.memory-limit`, `…pids-limit`, `…cpus`). The daemon lives *inside* that sandbox and the
script is its child, so everything arriving over the socket is attacker-influenced data about the
run: recorded, never trusted. The residual gap — a push is itself unauthenticated, and running
repo-committed scripts is the feature — is a known, documented issue.

**The daemon is pinned per run.** `qits.ci.daemon-version` is resolved once when a run is created,
recorded on the run row, and injected into every one of that run's containers, so a deploy landing
mid-run cannot make step 3 speak a different protocol than step 1. With the shipped url template
that version is the binary's **sha256** and the download is qits-artifacts' OCI blob route, so the
version pin and the integrity pin are one field. It ships **blank**, which yields a url that 404s and
the honest never-registered failure state rather than a default this repo invented; a deployment sets
it together with the daemon it deployed. Deployments not on `qits-net` under the standard aliases
also need `qits.ci.container-daemon-url`. Both are documented where they are shipped, in the `ci`
jar's `META-INF/microprofile-config.properties`.

**Failures stay distinguishable.** Docker refusing the launch, a container whose bootstrap never
produced a daemon (its own `docker logs` tail is captured *before* the reap and becomes the step's
output), a daemon that registered and then went quiet, a structured setup failure, a lost socket and
a genuine step timeout are six different recorded outcomes — none of them is "the step failed with
exit −1".

## Following a run, and stopping one

`GET /ci/api/runs/{runId}` is the whole live surface: while the run is `RUNNING` it carries a `live`
object — the step index in flight and the bounded tail it has printed. **Poll it.** There is no SSE
and no WebSocket on the read side; the daemon makes live output possible, it does not oblige a push
transport. The relay is memory and dies with the process — the persisted tail on each step row is
the record.

`POST /ci/api/runs/{runId}/cancel` answers 202 and asks the in-flight container to stop. The step it
was on is recorded `FAILED` with "cancelled" in its output and the rest `SKIPPED`. Cancelling a run
that is not running is a 409. It is the one operation here a person invokes on purpose, so — unlike
the intake and the run reads — it is **not** hidden from `docs/openapi.yml`.

A restart mid-run costs that run, honestly: on boot, runs left `RUNNING` are marked `FAILED`,
containers carrying the `qits.ci.run` label are removed, and a daemon from a previous life that
dials in presents a secret this process does not know and is closed 1008. No durability is added for
this by design — the launch table is memory, and that is what makes the restart story free.

## Deploying it

- Set `qits.ci.git-host-url` / `qits.ci.container-git-url` to the git host as reachable from the ci
  host and from a step container respectively. **Both end at the service, not at `/git`** — ci
  appends `/git/<repoId>` itself, because `/git` is the codebase's segment for the smart-HTTP wire
  protocol while *which* service hosts it is a deployment fact. The git host is qits-artifacts,
  which serves it under its own gateway segment, so the value is `http://qits-artifacts:8080/artifacts`
  and a fetch lands on `/artifacts/git/<repoId>`. The container-side alias only resolves on the
  network ci itself is on, so `qits.ci.network` must be set together with it.
- Set `qits.ci.token` and configure the git host's notifier with the same value. Blank is the
  dev/test default and means *no guard*.
- Allow-list `/ci/api/events/` for unauthenticated access — the caller is the git host's hook, a
  different process with no user session. That allowlist is qits-gateway's `PublicPaths`.
- Keep the run **read** surface behind the deployment's auth policy. It is not token-guarded, and it
  returns build logs.
- Set `qits.artifacts.registry-host` / `qits.artifacts.image-repository` to qits-artifacts' registry
  as reachable **from the docker host** — the daemon on the far side of the mounted socket is what
  resolves that name and performs the push, not this process and not the step's CLI. While the
  registry speaks plain HTTP the daemon also needs it in `insecure-registries`. Same class of fact as
  the socket mount, and now the same socket serves both. qits-cd ships the same two keys and derives
  its pull references from them, so the two services must agree.
- Leave `qits.artifacts.npm.hosted-url` / `qits.artifacts.npm.proxy-url` alone on a deployment where
  qits-artifacts answers to its usual alias: they are reached **from a step container**, on
  `qits.ci.network`, so the shipped defaults are already the right values and the host-published
  address used for `registry-host` is exactly the wrong one to copy here. Override them only when
  qits-artifacts moves off the alias.
- Give the process a persistent `~/.qits/data/ci` (or override `quarkus.datasource.ci.jdbc.url` and
  `qits.ci.data-dir`). The H2 there is a plain **single-writer file** — no `AUTO_SERVER`, so nothing
  else may open it while ci runs, and nothing listens on a database port.
- Point `QITS_EVENTS_URL` (`qits.events.url`) at qits-events — scheme, host and port, **no path**:
  the client appends `/events/api/events/{id}` and `/events/stream` itself, and a path here yields a
  doubled one and a 404 nothing retries out of. The shipped default is the qits-net alias
  `http://qits-events:8080`, which is already right for a deployment on that network and wrong for a
  host-run process. `qits.eventsourcing.enabled=false` turns the whole thing off, which is what a
  deployment with no qits-events wants — the keys and their defaults are the qits-eventsourcing
  jar's, not this module's.
- **Set `QUARKUS_DATASOURCE_EVENTSOURCING_JDBC_URL` — this one is not optional in a container**, and
  it is the exact counterpart of the `QUARKUS_DATASOURCE_CI_JDBC_URL` a deployment already sets:
  `jdbc:h2:file:/data/eventsourcing/h2/eventsourcing`, on the same data volume. The shipped default
  is `${user.home}/.qits/…`, and in a container with no `HOME` the native binary resolves
  `user.home` to `?`, which H2 refuses outright — *"A file path that is implicitly relative to the
  current working directory is not allowed"* — so the process **fails to boot**, at Flyway, before
  anything serves. Measured, on the first rollout of this change: loud rather than silent, and cd
  keeps the previous container while the new one restarts, but a deployment that adds the eventsourcing
  module without adding this variable does not come up. The outbox itself is a second single-writer
  H2 beside ci's own with its own Flyway lineage, holding exactly the events a publish could not
  deliver — empty in a healthy process, so losing the *file* is survivable in a way that omitting the
  *variable* is not.

## What is deliberately *not* here

The git host and its post-receive hook (qits-artifacts), the repository and workspace contexts, and
anything to do with running a pipeline inside a workspace container. Pipelines run in their own
throwaway containers; a workspace is never involved.

Retries, a non-advisory gate, and clone/dependency caching across the per-step containers are
follow-ups, not omissions of the extraction. Per-step timeouts are not: a step may declare
`timeout-seconds:` in `.config/qits/ci-post-receive.yml`, and one that declares none gets
`qits.ci.step-timeout-seconds`.
