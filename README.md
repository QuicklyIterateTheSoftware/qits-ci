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
| `service/` | `eu.wohlben.qits.ci.api` — the JAX-RS event intake, the run read surface, the token filter and the exception mapper. |

`ci/` is a library jar. **`service/` is the application** — augmented by the `quarkus-maven-plugin`
into a process:

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar   # :8080, intake on /ci/api/events/post-receive

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
| out | where the git host answers, for ci's **own** `git fetch` of the pushed ref — ci appends `/git/<repoId>` | `qits.ci.git-host-url` |
| out | the same, as reachable **from a step container** on the shared network | `qits.ci.container-git-url` |

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

## Host-side by design

This context runs entirely **outside** any workspace container:

- `CiDockerRunner` spawns one ephemeral `docker run --rm` per step on `qits.ci.network`, with the
  git clone of the pushed sha done by the container's own prelude.
- `GitConfigFetcher` shells ci's own host `git` against its bare cache.

That is the boundary, not residue of the monorepo. A step's script is **repo-controlled code**, so
the step container is treated as a hostile-code sandbox: `--cap-drop=ALL`, `no-new-privileges`, no
docker socket, and memory/pids/cpu caps (`qits.ci.memory-limit`, `…pids-limit`, `…cpus`). The
residual gap — a push is itself unauthenticated, and running repo-committed scripts is the feature —
is a known, documented issue.

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

## What is deliberately *not* here

The git host and its post-receive hook (qits-artifacts), the repository and workspace contexts, and
anything to do with running a pipeline inside a workspace container. Pipelines run in their own
throwaway containers; a workspace is never involved.

Per-step timeouts, retries, and a non-advisory gate are follow-ups, not omissions of the extraction.
