# qits-ci

The **in-repo CI pipeline**: a repository opts in by committing
`.config/qits/ci-post-receive.yml`; when a push lands, ci reads that config back out of the pushed
commit, runs each step's script in a fresh container of the step's declared image, and records a
per-step pass/fail for the push — advisory, queryable over REST.

    mvn verify        # a clone of this repo alone builds and tests green — no monorepo, no docker

## Layout

| Module | What |
|---|---|
| `ci/` | `eu.wohlben.qits.ci.*` — entity, persistence, dto, mapper, control, error. The pipeline itself. No web, no JAX-RS. |
| `service/` | `eu.wohlben.qits.ci.api` — the JAX-RS event intake, the run read surface, the token filter and the exception mapper — plus `…ci.daemonhost`, the step-container control plane (below). |
| `ci-daemon-protocol/` | `eu.wohlben.qits.cidaemon.protocol` — the ci-daemon wire contract, **vendored** from [qits-ci-daemon](https://github.com/QuicklyIterateTheSoftware/qits-ci-daemon) and never edited here. Framework-free; `diff -r` is the drift detector. |

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

ci never touches the bare origins on disk: it keeps its **own** bare cache per repository under
`<qits.ci.data-dir>/repos/<repoId>.git` and fetches into it over the git host's URL. That is what
lets it run on a machine with no shared filesystem with qits.

The fetch asks for the **branch ref**, not the bare sha — an unadvertised-object fetch would mean
relaxing the git host's want policy for every unauthenticated client. ci then verifies the pushed
sha is still an ancestor of the fetched tip: a racing push still runs, a force-pushed-away commit
records nothing rather than a spurious red run.

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

`GitConfigFetcher` shells ci's own host `git` against its own bare cache, and that plus the docker
CLI is the entire set of processes qits-ci spawns. Its docker vocabulary is container lifecycle:
`run`, `logs`, `rm`, `ps`, `network inspect`/`create`. `exec` is not in it, not even to deliver the
daemon binary.

A step's script is **repo-controlled code**, so the step container is a hostile-code sandbox:
`--cap-drop=ALL`, `no-new-privileges`, no docker socket, and memory/pids/cpu caps
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
- Give the process a persistent `~/.qits/data/ci` (or override `quarkus.datasource.ci.jdbc.url` and
  `qits.ci.data-dir`). The H2 there is a plain **single-writer file** — no `AUTO_SERVER`, so nothing
  else may open it while ci runs, and nothing listens on a database port.

## What is deliberately *not* here

The git host and its post-receive hook (qits-artifacts), the repository and workspace contexts, and
anything to do with running a pipeline inside a workspace container. Pipelines run in their own
throwaway containers; a workspace is never involved.

Retries, a non-advisory gate, and clone/dependency caching across the per-step containers are
follow-ups, not omissions of the extraction. Per-step timeouts are not: a step may declare
`timeout-seconds:` in `.config/qits/ci-post-receive.yml`, and one that declares none gets
`qits.ci.step-timeout-seconds`.
