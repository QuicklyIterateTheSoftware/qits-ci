# qits-ci as a GraalVM-native container IMAGE — the same shape as qits-artifacts', qits-gateway's
# and qits-workspace-daemon's docker/Dockerfile: a Mandrel builder stage compiles the binary, a
# minimal runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative` produces `service/target/qits-ci`
# in about two minutes with no container involved. What this adds is the image around it — and a way
# for a machine with no GraalVM to get a binary at all, since the builder stage brings its own
# native-image.
#
# Build from the repo root:
#
#     git submodule update --init
#     (cd service/src/main/webui && npm ci && npm run build)
#     docker build -t qits/ci:latest -f docker/Dockerfile .
#
# The first two lines are not optional and are not a convenience. The init covers BOTH submodules
# and each is load-bearing here for a different reason: this image carries the ci client
# (service/src/main/webui = the qits-spa-ci submodule) and the builder stage below packages a bundle
# that was ALREADY BUILT rather than building one — see the Quinoa flags on the mvnw line for why
# the build cannot happen inside that stage — while `eventstream/` (the qits-eventstream submodule)
# is a reactor module the `-am` build compiles from source, so an uninitialised one stops maven at
# `Child module … does not exist` before anything is packaged.
#
# --- this image does NOT run with an empty environment, and that is not a packaging bug ------------
# `docker run --rm qits/ci:latest` dies before the HTTP listener with
#
#   FlywaySqlUnableToConnectToDbException: ... A file path that is implicitly relative to the current
#   working directory is not allowed in the database URL "jdbc:h2:file:?/.qits/data/ci/h2/ci"
#
# That `?` is `${user.home}`. UID 1001 has no /etc/passwd entry in ubi-minimal, and a native image
# resolves user.home through getpwuid(2) rather than $HOME — so exporting HOME does not fix it, and
# the two `${user.home}`-rooted defaults the `ci` jar ships (the H2 file and `qits.ci.data-dir`, the
# parent of the per-repo bare git caches) have nowhere to land.
#
# An /etc/passwd line for 1001 would make it boot with no environment at all. IT IS DELIBERATELY NOT
# HERE, and re-adding it is not a fix. The H2-on-local-disk arrangement is a PROTOTYPE — one file
# database, single writer, sitting next to the process — and not a committed data architecture. An
# image that comes up healthy on an empty environment resolves both of those paths into the
# container's own writable layer: it presents the prototype as decided, and it silently discards
# every recorded run and every bare cache on the next redeploy. Booting unconfigured is not a
# supported mode of this image. A container that refuses to start is the failure you see; one that
# starts on ephemeral storage is the failure that hides.
#
# So a deployment mounts a volume at /data (which this image owns, see the runtime stage) and names
# the database. This is what the boot needs:
#
#   docker run -v qits-ci-data:/data \
#     -e QUARKUS_DATASOURCE_CI_JDBC_URL=jdbc:h2:file:/data/ci/h2/ci \
#     ...
#
# `qits.ci.data-dir` is NOT in that list: the image names it (/data/ci, see the runtime stage). It is
# where GitConfigFetcher's per-repository bare caches live — files on disk, nothing of it in SQL — so
# it is not part of the prototype and simply works. The two are separable precisely because the H2
# default interpolates `${user.home}` directly rather than deriving from that key, so naming the data
# dir cannot accidentally revive the database.
#
# Four more have defaults that are wrong QUIETLY — nothing will tell you they are unset:
#
#   QITS_CI_GIT_HOST_URL        where ci's own git fetches the pushed ref. The default is
#                               localhost:8080/artifacts, which standalone means THIS service — and
#                               this service hosts no git, so the fetch 404s and every push records
#                               "no pipeline", indistinguishable from a repository that never opted
#                               in. Point it at qits-artifacts on qits-net.
#   QITS_CI_CONTAINER_GIT_URL   the same base as reachable from inside a step container. Its
#                               host.docker.internal default assumes host-run docker.
#   QITS_CI_NETWORK             the network step containers join; it has to be the one the alias
#                               above resolves on.
#   QITS_AUTH_MACHINE_REQUIRED  false (the default) means the event intake — which triggers
#                               repo-controlled code — takes any caller on the network. true demands
#                               a qits-idp bearer, and needs a reachable idp that grants the git
#                               host's client a project claim.
#   QUARKUS_OIDC_CLIENT_CLIENT_ENABLED / QUARKUS_OIDC_CLIENT_CREDENTIALS_SECRET
#                               the other direction: present a bearer to qits-cd. A separate switch,
#                               so either end of the platform can be turned on first.
#
# See README.md, "Deploying it", for the whole surface.
#
# --- READ THIS BEFORE DEPLOYING: ci is not a self-contained process -------------------------------
# ci is HOST-SIDE by design (README.md, "How a step runs") — it does its work by shelling out,
# so the runtime stage below is deliberately NOT the usual "the binary and nothing else". Two
# external programs are part of this service's *runtime*, and neither is something a config value
# can substitute:
#
#   git     `GitConfigFetcher` shells ci's own git against a per-repo bare cache to read
#           `.config/qits/ci-post-receive.yml` back out of the pushed commit. It runs on EVERY
#           intake event, before any step exists. Absent, a fetch fails and every push records "no
#           pipeline" — the same silent shape as a wrong git-host url above. Installed below, from
#           the image's own UBI repositories.
#
#   docker  `CiDaemonLauncher` starts one detached container per step (and removes it again, and
#           sweeps orphans at boot); its `ensureNetwork()` observer fires on `StartupEvent`. The CLI
#           is installed below — but it is only half of what a deployment has to supply, and the
#           other half is a decision, not a default:
#
#             THE DOCKER SOCKET IS NOT IN THIS IMAGE. Run with
#             `-v /var/run/docker.sock:/var/run/docker.sock`, or ci can start no step.
#
#           That mount hands this container control of the host's docker daemon, which is
#           root-equivalent. This repo is emphatic that a *step* container never gets the socket
#           (README.md, "How a step runs"); ci itself needs it, and that asymmetry is the whole
#           trust boundary, so it is left as an explicit deployment act rather than baked in.
#           Without it the service still starts and serves: `ensureNetwork` logs one WARN and each
#           step fails with a docker connection error instead of a pipeline result.
#
#           Only docker's CLI is here. `qits.ci.container-runtime` names the binary ci invokes, so
#           pointing it at anything else makes supplying that CLI the deployment's problem.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `-am` builds `service`'s dependency from source —
# `qits-ci-domain` is a 1.0.0-SNAPSHOT this repo never publishes (no distributionManagement, no
# <repositories>), so a builder container with an empty ~/.m2 has nowhere else to get it. Copying
# module by module would mean editing this file every time one is added, and forgetting to would
# fail the build with an unresolved dependency rather than anything that names the real cause.
# `.dockerignore` is what keeps this cheap — without it this line drags in every target/ dir, and a
# stale one can shadow what this stage compiles.
COPY . .

# -Dnative flips quarkus.native.enabled (the `native` profile lives in service/pom.xml, since only
# that module is an application). Cap native-image RAM so the build is deterministic on small
# machines.
#
# Tests are skipped because `./mvnw verify` on a clone is their gate (AGENTS.md) and re-running them
# here proves the same thing more slowly. Note the profile also flips `skipITs` to false — stopping
# at `package` rather than `verify` is what keeps `CiPackagedSurfaceIT` out of the image build; it
# wants a free port and a writable home, neither of which this stage is the right place to arrange.
#
# --- the Quinoa flags, and why this stage no longer builds the client -----------------------------
#
# package-manager-install: the Mandrel builder image ships no node at all, so this stage installs its
# own — from nodejs.org, which a docker build can reach. It lives here rather than in
# application.properties on purpose: a local or CI `mvnw` build must use the node on PATH, so that no
# build silently downloads a toolchain. 22.22.0 is the PLATFORM pin, the node every other qits
# service's builder stage installs and the version a developer's host carries. Note it is
# deliberately NOT the step container's node: node-docker-base is node:24-alpine, and the bundle this
# image consumes was built there. The two do not have to agree, because this stage does not build the
# bundle — the commands below are neutered to `--version`, so the only thing node does here is exist
# and prove its package manager runs.
#
# The three command overrides are the load-bearing part, and the reason this file deviates from the
# platform's Quinoa recipe (docs/project-setup-quinoa-angular.md in the qits monorepo, which has the
# builder run the client's real install and build):
#
#   service/src/main/webui is qits-spa-ci, and it depends on @qits/ui-components. That package exists
#   ONLY on the platform's own npm registry (qits-artifacts). A RUN step in a docker build reaches
#   the public internet — Maven Central, npmjs.org, nodejs.org — and can reach the platform registry
#   by NO address: not the qits-net alias, not localhost:8081, not host.docker.internal. So the
#   install cannot happen here — it hangs on the localhost:8081 the submodule's own .npmrc names and
#   then dies on npm's `Exit handler never called!`, which says nothing about why — and an install
#   that "worked" here would have resolved the @qits scope against npmjs.org, where that name does
#   not exist and never will.
#
# The bundle is therefore built BEFORE this image, by the pipeline step in
# .config/qits/ci-post-receive.yml, which runs on qits-net where the registry is reachable, and
# arrives in the build context as service/src/main/webui/dist/ (see .dockerignore). `--version` is
# the null command: it proves the package manager is really there and does nothing else.
#
# THIS DOES NOT FAIL QUIETLY. The `test -f` below stops a context with no bundle before the
# multi-minute native compile, and Quinoa's own build-dir check would stop it after. A forgotten
# `npm run build` in the step is a red build, not a service that ships without its client. (The same
# guard covers the case this comment used to describe on its own: a webui submodule that is NOT
# initialised, which git leaves as an empty directory and Quinoa rejects with `No package.json found
# in Web UI directory: 'src/main/webui'` — see AGENTS.md, "The Angular client".)
#
# --- and why the bundle is copied onto itself first -----------------------------------------------
# Quinoa does not copy build-dir into target/quinoa/build, it MOVES it, and overlayfs cannot rename a
# directory that still lives in a LOWER image layer. It answers EXDEV, the JDK falls back to its
# cross-file-store path, and that path refuses a non-empty directory — the build dies seconds in with
# `DirectoryNotEmptyException: …/dist/qits-spa-ci/browser`, which names the source and explains
# nothing. A builder stage that *creates* dist itself is immune by accident; ours arrives by COPY.
#
# One `cp` re-materialises it in the layer that is about to move it. It has to be in THIS `RUN` and
# not a tidier one of its own — a layer boundary is exactly what it is undoing.
RUN d=service/src/main/webui/dist/qits-spa-ci/browser; \
    test -f "$d/index.html" || { \
      echo "No built client at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in service/src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -pl service -am package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version

# --- runtime --------------------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.6
WORKDIR /work

# git-core rather than git: ci runs `init --bare`, `fetch`, `show`, `cat-file` and `merge-base` and
# nothing that needs perl, the remote helpers or the docs — see the header for why it is not
# optional. The docker CLI comes from Docker's own repository because no UBI repository carries a
# docker-compatible client at all; the repo definition is removed again afterwards so the shipped
# image does not carry a third-party package source it has no reason to consult.
#
# No ENV HOME accompanies them. Checked rather than assumed: run as 1001 with HOME=/ (unwritable,
# which is what an image with no passwd entry gets) both programs do everything ci asks of them. And
# a HOME here would read like the user.home workaround the header refuses, which is worse than
# useless — it does not make user.home resolve either.
RUN printf '[docker-ce-stable]\nname=Docker CE Stable\nbaseurl=https://download.docker.com/linux/centos/9/$basearch/stable\nenabled=1\ngpgcheck=1\ngpgkey=https://download.docker.com/linux/centos/gpg\n' \
      > /etc/yum.repos.d/docker-ce.repo \
 && microdnf -y --setopt=install_weak_deps=0 --nodocs install git-core docker-ce-cli \
 && microdnf -y clean all \
 && rm -f /etc/yum.repos.d/docker-ce.repo

# `quarkus.package.output-name` names the binary `qits-ci` (no -runner suffix), so the process is
# identifiable in `ps`/`docker top` — which matters more here than elsewhere, because this service's
# own work is spawning `docker run` children and a step's process tree gets read while debugging a
# run. That name is spelled a third time in service/pom.xml as failsafe's `native.image.path`; all
# three move together.
COPY --from=build --chown=1001:root /src/service/target/qits-ci /work/qits-ci
RUN chmod +x /work/qits-ci

# The mount point for QITS_CI_DATA_DIR and the H2 url, created here so it is OWNED. Docker seeds a
# fresh named volume from whatever the image has at the mount path, ownership included; with no such
# directory it makes one root-owned 0755, and the boot then dies on an AccessDeniedException rather
# than on the config error the header documents — which reads like a bug in this image and is not
# one. Group-writable and group-root because a ubi image is meant to survive being assigned an
# arbitrary UID with GID 0. (A bind mount still takes its ownership from the host — nothing in an
# image can fix that.)
RUN mkdir -p /data/ci && chown -R 1001:root /data && chmod -R 775 /data

# The git caches, named here rather than left to a deployment. `qits.ci.data-dir` is where
# GitConfigFetcher keeps a bare cache per repository — files on disk, nothing of it in SQL — so it is
# not the prototype and it resolves correctly out of the box.
#
# The datasource is NOT set beside it, and that asymmetry is the point: the H2 default interpolates
# `${user.home}` DIRECTLY rather than deriving from this key, so naming the data dir cannot
# accidentally revive the database. The prototype still refuses to start until a deployment says
# where it goes.
ENV QITS_CI_DATA_DIR=/data/ci

EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the gateway, not a peer on qits-net.
ENTRYPOINT ["/work/qits-ci", "-Dquarkus.http.host=0.0.0.0"]
