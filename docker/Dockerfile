# qits-ci as a GraalVM-native container IMAGE — the same shape as qits-gateway's and
# qits-workspace-daemon's docker/Dockerfile: a Mandrel builder stage compiles the binary, a minimal
# runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative` produces `service/target/qits-ci`
# in about two minutes with no container involved. What this adds is the image around it — and a way
# for a machine with no GraalVM to get a binary at all, since the builder stage brings its own
# native-image.
#
# Build from the repo root:
#
#     docker build -t qits/ci:latest -f docker/Dockerfile .
#
# --- READ THIS BEFORE DEPLOYING: ci is not a self-contained process ------------------------------
# ci is HOST-SIDE by design (README.md, "Host-side by design") — it does its work by shelling out,
# so the runtime stage below is deliberately NOT the usual "the binary and nothing else". Two
# external programs are part of this service's *runtime*, and neither is something a config value
# can substitute:
#
#   git     `GitConfigFetcher` shells ci's own git against a per-repo bare cache to read
#           `.config/qits/ci-post-receive.yml` back out of the pushed commit. It runs on EVERY
#           intake event, before any step exists. Absent, a fetch fails and every push records "no
#           pipeline" — which is indistinguishable from a repository that never opted in, so CI
#           silently stops for repositories that did. Installed below, from the image's own UBI
#           repositories.
#
#   docker  `CiDockerRunner` spawns one `docker run --rm` per step, and its `ensureNetwork()`
#           observer fires on `StartupEvent`. The CLI is installed below — but it is only half of
#           what a deployment has to supply, and the other half is a decision, not a default:
#
#             THE DOCKER SOCKET IS NOT IN THIS IMAGE. Run with
#             `-v /var/run/docker.sock:/var/run/docker.sock`, or ci can start no step.
#
#           That mount hands this container control of the host's docker daemon, which is
#           root-equivalent. This repo is emphatic that a *step* container never gets the socket
#           (README.md, "Host-side by design"); ci itself needs it, and that asymmetry is the whole
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
RUN ./mvnw -B -ntp -pl service -am package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=4g

# --- runtime --------------------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.6
WORKDIR /work

# git-core rather than git: ci runs `init --bare`, `fetch`, `show`, `cat-file` and `merge-base` and
# nothing that needs perl, the remote helpers or the docs — see the header for why it is not
# optional. The docker CLI comes from Docker's own repository because no UBI repository carries a
# docker-compatible client at all; the repo definition is removed again afterwards so the shipped
# image does not carry a third-party package source it has no reason to consult.
RUN printf '[docker-ce-stable]\nname=Docker CE Stable\nbaseurl=https://download.docker.com/linux/centos/9/$basearch/stable\nenabled=1\ngpgcheck=1\ngpgkey=https://download.docker.com/linux/centos/gpg\n' \
      > /etc/yum.repos.d/docker-ce.repo \
 && microdnf -y --setopt=install_weak_deps=0 --nodocs install git-core docker-ce-cli \
 && microdnf -y clean all \
 && rm -f /etc/yum.repos.d/docker-ce.repo

# `quarkus.package.output-name` names the binary `qits-ci` (no -runner suffix), so the process is
# identifiable in `ps`/`docker top` — which matters more here than elsewhere, because this service's
# own work is spawning `docker run` children and a step's process tree gets read while debugging a
# run.
COPY --from=build --chown=1001:root /src/service/target/qits-ci /work/qits-ci
RUN chmod +x /work/qits-ci

# uid 1001 needs a passwd entry, and this is not cosmetic. `qits.ci.data-dir` and the H2 url are
# both `${user.home}`-rooted defaults from the `ci` jar's microprofile-config.properties, and a
# native image resolves `user.home` through getpwuid(2) — NOT from $HOME. With no entry for the uid
# it resolves to the literal `?`, and the binary dies at boot before it listens:
#
#     Unable to obtain connection from database: A file path that is implicitly relative to the
#     current working directory is not allowed in the database URL
#     "jdbc:h2:file:?/.qits/data/ci/h2/ci"
#
# Setting `ENV HOME` alone does not fix that (it was tried); it is set anyway because the two
# programs ci shells out to do read $HOME — git for its config, the docker CLI for ~/.docker.
#
# ci's whole state then lands under /work/.qits/data/ci: the H2 file and the per-repo bare git
# caches. That path is CONTAINER-LOCAL. Mount a volume on it, or a redeploy discards every recorded
# run — and, since the caches go with it, the next event re-clones every repository it sees.
RUN echo 'qits:x:1001:0:qits:/work:/sbin/nologin' >> /etc/passwd \
 && chown -R 1001:root /work \
 && chmod -R g=u /work
ENV HOME=/work

EXPOSE 8080
USER 1001

# `qits.ci.git-host-url`, `qits.ci.container-git-url`, `qits.ci.network` and `qits.ci.token` all
# carry defaults that were right for an in-process ci and are wrong for a deployed one; a fetch
# against the default lands on this service, which hosts no git. See README.md, "Deploying it".
ENTRYPOINT ["/work/qits-ci", "-Dquarkus.http.host=0.0.0.0"]
