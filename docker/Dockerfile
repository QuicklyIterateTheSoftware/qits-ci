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
#     git submodule update --init service/src/main/webui
#     (cd service/src/main/webui && npm ci && npm run build)
#     docker build -t qits/ci:latest -f docker/Dockerfile .
#
# The init is load-bearing: this image carries the ci client (service/src/main/webui), and the
# builder stage packages a bundle that was already built.
#
# --- this image does NOT run with an empty environment, and that is not a packaging bug ------------
# `docker run --rm qits/ci:latest` dies before the HTTP listener, on an unresolvable config
# expression naming QITS_RESOURCE_DB_URL. That is the refuse-to-boot stance, and it is deliberate:
# both stores this process opens — its own `ci` database and the qits-eventstream outbox — read a
# QITS_RESOURCE_<NAME>_URL / _USERNAME / _PASSWORD triple with NO default behind it, so a container
# that has not been given a database has nothing to be usefully started as.
#
# THIS IMAGE HAS NO STATE ON DISK AT ALL ANY MORE, and both of the things that used to make it need
# some are gone. It kept a bare git mirror per repository under `qits.ci.data-dir` until it started
# reading pipeline config off the git host's content routes; and it kept a file H2 under
# `${user.home}` until the platform grew resource provisioning. That H2 is what made an empty
# environment fail on a path rather than on a name — UID 1001 has no /etc/passwd entry in
# ubi-minimal, a native image resolves user.home through getpwuid(2) rather than $HOME, so
# `${user.home}` came out `?` and no exported HOME could fix it. Nothing resolves user.home now, and
# there is no /data mount to arrange.
#
# What a deployment supplies instead is two triples, and it does not type them by hand: this
# repository declares
#
#   resources: postgresql:db, postgresql:eventstream:qits_ci_eventstream
#
# in .config/qits/deployments.yml, and qits-platform-deployments creates the roles and the databases
# on the tier's postgres before this container starts and injects the six variables into it. A
# `docker run` by hand supplies them itself:
#
#   docker run \
#     -e QITS_RESOURCE_DB_URL=jdbc:postgresql://<host>:5432/qits_ci \
#     -e QITS_RESOURCE_DB_USERNAME=qits_ci -e QITS_RESOURCE_DB_PASSWORD=... \
#     -e QITS_RESOURCE_EVENTSTREAM_URL=jdbc:postgresql://<host>:5432/qits_ci_eventstream \
#     -e QITS_RESOURCE_EVENTSTREAM_USERNAME=qits_ci_eventstream \
#     -e QITS_RESOURCE_EVENTSTREAM_PASSWORD=... \
#     ...
#
# These have defaults that are wrong QUIETLY — nothing will tell you they are unset:
#
#   QITS_CI_GIT_HOST_URL        where ci reads the pushed commit's pipeline config. The default is
#                               localhost:8080/artifacts, which standalone means THIS service — and
#                               this service hosts no git, so the read 404s and every push records
#                               "no pipeline", indistinguishable from a repository that never opted
#                               in. Point it at qits-artifacts on qits-net.
#   QITS_CI_CONTAINER_GIT_URL   the same base as reachable from inside a step container.
#   QITS_CI_NETWORK             the network step containers join; it has to be the one the alias
#                               above resolves on.
#   QITS_AUTH_MACHINE_REQUIRED  false (the default) means the event intake — which triggers
#                               repo-controlled code — takes any caller on the network. true demands
#                               a qits-idp bearer, and needs a reachable idp that grants the git
#                               host's client a project claim.
#   QITS_EVENTS_URL             where a green run is announced. There is no deployer address here
#                               any more: qits-ci publishes BuildSuccessful and
#                               qits-platform-deployments subscribes, so an unset or wrong value
#                               costs every deployment on the platform, not one call.
#
# See README.md, "Deploying it", for the whole surface.
#
# --- this image ships the binary alone -------------------------------------------------------------
# ci spawns no process. Step containers start through qits-containers over HTTP (README.md, "How a
# step runs"), so the runtime stage carries no docker CLI and the deployment mounts no docker socket.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.dev.localhost:8080/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the
# first build fetches the image from upstream and caches it, every later build is served from local
# disk. The tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when
# quay moves it.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
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
#   by NO address: not the qits-net alias, not the registry vhost, not host.docker.internal. So the
#   install cannot happen here — it hangs on the registry.dev.localhost:8080 the submodule's own
#   .npmrc names and then dies on npm's `Exit handler never called!`, which says nothing about why
#   — and an install that "worked" here would have resolved the @qits scope against npmjs.org,
#   where that name does not exist and never will.
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
ARG QITS_MAVEN_REPOSITORY_URL=http://registry.dev.localhost:8080/artifacts/maven/maven
ENV QITS_MAVEN_REPOSITORY_URL=$QITS_MAVEN_REPOSITORY_URL
#
# --- and where Maven Central is mirrored, when it is ----------------------------------------------
# The pipeline passes the platform's pull-through (qits-platform-mirror's central proxy, the same
# authority the FROM lines above pull through); EMPTY — the default — means direct Maven Central.
# `.qits-maven-settings.xml` activates its central-proxy profile only on a non-empty value, so a
# bootstrap or hand build that passes nothing resolves exactly as it always did.
ARG QITS_MAVEN_CENTRAL_URL=
ENV QITS_MAVEN_CENTRAL_URL=$QITS_MAVEN_CENTRAL_URL
#
# --- and how Maven authenticates, once that repository stops answering anonymously ----------------
# The two secret mounts put the CI run's commissioned client into THIS `RUN`'s environment and
# nowhere else — not a layer, not the image history. That is why they are not build args: this image
# is pullable, and a build arg is recorded where anyone who pulls it can read it.
# `.qits-maven-settings.xml` reads the two variables as the `qits-maven-network` server.
#
# Absent secrets leave both variables unset, which is the state every build is in today and the
# state a host-side `docker build` is in permanently: Maven sends credentials only when a repository
# answers 401, and the registry does not yet.
RUN --mount=type=secret,id=qits-client-id,env=QITS_MAVEN_AUTH_USR \
    --mount=type=secret,id=qits-client-secret,env=QITS_MAVEN_AUTH_PSW \
    d=service/src/main/webui/dist/qits-spa-ci/browser; \
    test -f "$d/index.html" || { \
      echo "No built client at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in service/src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version

# --- runtime --------------------------------------------------------------------------------------
# `mirror.dev.localhost:8080/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# NO PACKAGES ARE INSTALLED HERE, and two used to be. A docker CLI stood here until step containers
# started through qits-containers over HTTP; `git-core` stood beside it, for the bare mirror ci
# fetched pipeline config into, until that config became an HTTP read off the git host's content
# routes. Neither is kept "just in case": what a runtime image carries is what a deployment has to
# trust, and nothing here shells anything any more.

# `quarkus.package.output-name` names the binary `qits-ci` (no -runner suffix), so the process is
# identifiable in `ps`/`docker top` while debugging a container. That name is spelled a third time in
# service/pom.xml as failsafe's `native.image.path`; all three move together.
COPY --from=build --chown=1001:root /src/service/target/qits-ci /work/qits-ci
RUN chmod +x /work/qits-ci

# NO /data, AND NOTHING TO OWN. This image used to create and chown a mount point for the H2 file,
# so that a fresh named volume was seeded with the right ownership rather than landing root-owned and
# failing the boot on an AccessDeniedException. The store is a postgres database now — see the
# header — so there is no volume to mount, no directory to own, and this image writes nothing outside
# its own process.

EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the gateway, not a peer on qits-net.
ENTRYPOINT ["/work/qits-ci", "-Dquarkus.http.host=0.0.0.0"]
