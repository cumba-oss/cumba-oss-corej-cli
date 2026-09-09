# syntax=docker/dockerfile:1
#
# Containerised coreJ CDISC validation CLI. The container is a one-shot command:
# its entrypoint is the CLI launcher, so whatever you pass after the image name is
# forwarded verbatim to `CdiscValidate`.
#
#   docker build -t cumba-oss-corej-cli:local .
#   docker run --rm cumba-oss-corej-cli:local --help
#
# ⚠ The rule corpus is NOT in this image — see "The stores" below. Supply it:
#   docker run --rm -v corej-rules:/app/rules -v "$PWD":/data \
#       cumba-oss-corej-cli:local -rp cdisc-sdtmig-3-4 -d /data/datasets \
#       -o /data/CORE-Report.json
#
# ⚠⚠ The build context is THIS REPOSITORY — `docker build .` from here, and
# docker-compose.yml sets `context: .`. The repository is flat and single-module,
# so it builds itself, and the .dockerignore beside this file governs the context.

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-25
ARG RUNTIME_IMAGE=eclipse-temurin:25-jre

# ---------- build stage ----------
FROM ${MAVEN_IMAGE} AS build
WORKDIR /build

# The whole repository. .dockerignore keeps target/, .git/ and the compose bind
# mount out, so this stays small and cache-friendly.
COPY . .

# One `package` produces the runnable bundle: this project is the application AND
# its own dist assembly (src/assembly/dist.xml, bound to the package phase), so
# there is no separate dist module and no `-pl`/`-am` reactor selection. The
# assembly ships a zip whose single top-level directory is the bundle; extract it
# with the JDK `jar` tool (present in this JDK image, absent from the JRE runtime
# image) into a fixed /build/out so the runtime stage can COPY a stable path.
# BuildKit's cache mount keeps ~/.m2 warm across rebuilds.
#
# ⚠ No `-P main` here. This repository's reactor is deliberately profile-less —
# CI strictness lives in the workflow's flags, not in a profile — so naming a
# profile that does not exist would fail the build.
#
# Tests are skipped: CI gates them, and an image build is not the place to
# discover a red suite.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests package \
 && mkdir -p /build/extract /build/out \
 && ( cd /build/extract && jar xf "$(ls /build/target/cumba-oss-corej-cli-*.zip)" ) \
 && cp -a /build/extract/cumba-oss-corej-cli-*/. /build/out/

# ---------- runtime stage ----------
FROM ${RUNTIME_IMAGE} AS runtime

# Non-root runtime user, pinned to uid/gid 1000. A host directory bind-mounted at
# /data keeps the host's ownership, so the in-container user must share the uid
# that owns the host dir; 1000 is the typical first host user, so
# `chown -R 1000:1000 <hostdir>` makes outputs writable. Ubuntu-based bases
# (eclipse-temurin is Ubuntu) ship a default uid/gid-1000 user ('ubuntu'); free up
# 1000 first so the pin can't collide.
RUN set -eu; \
    existing_user="$(getent passwd 1000 | cut -d: -f1 || true)"; \
    [ -z "$existing_user" ] || userdel -r "$existing_user" 2>/dev/null || userdel "$existing_user" 2>/dev/null || true; \
    existing_group="$(getent group 1000 | cut -d: -f1 || true)"; \
    [ -z "$existing_group" ] || groupdel "$existing_group" 2>/dev/null || true; \
    groupadd --gid 1000 corej; \
    useradd --uid 1000 --gid 1000 --home-dir /app --shell /usr/sbin/nologin corej

# The exploded dist bundle: cumba-oss-corej-cli.jar (the cumba-oss-bootstrap
# launcher) and its sidecar .conf, flat lib/, run.sh, and the three EMPTY store
# directories the bundle ships (rules/ rules-define/ dictionaries/, each holding a
# README that says what belongs there and where to get it). Those empties are
# load-bearing — see below.
COPY --from=build /build/out /app/dist

COPY docker-entrypoint.sh /app/docker-entrypoint.sh

# ---------- The stores ----------
#
# ⚠⚠ No rule corpus ships in this image. The corpora are released on their own
# cadence by cumba-oss-corej-rules and are not Maven dependencies, so there is
# nothing for the build stage to copy. The operator supplies them.
#
# ⚠⚠ "Configured but MISSING" is a hard error for two of the three: the dictionary
# store throws when a validation run sets up, and the Define-XML corpus throws on
# every local-mode -vx run. Only rules/ treats missing and empty alike. That is why
# these directories are created below, and why docker-entrypoint.sh falls back to
# the bundle's own empty copies rather than leaving a dangling path. An EMPTY store
# degrades to a loud per-rule SKIP; a MISSING one throws.
#
# ⚠ These defaults deliberately sit under /app, not /data. The documented ad-hoc
# form is `docker run -v "$PWD":/data`, which mounts the CALLER'S working
# directory, and creating store directories in there would be rude. Mount what you
# want to keep:
#   docker run --rm -v corej-rules:/app/rules -v corej-dicts:/app/dictionaries \
#              -v corej-api-cache:/app/api-cache …
# The compose stack instead points all four at subdirectories of its own
# ./corej-data bind mount, where there is nothing to pollute.
#
# Precedence comes from the bundle's .conf: COREJ_* environment > -D system
# property > the bundle's own directory. So each ENV below overrides the bundle
# default, and `docker run -e` overrides the ENV.

# The data rule corpus (`-rp`, or `-s` + `-v`). Take the matching
# cumba-oss-corej-rules release asset and put its CONTENTS here, so this directory
# holds packages.json and rules-*.json directly. Missing or empty is tolerated — a
# run reports that it found no packages and names the directory it searched.
ENV COREJ_RULES_DIR=/app/rules

# The Define-XML conformance corpus, used only by `-vx` in local mode. A SEPARATE
# release asset from the corpus above. ⚠ Configured-but-missing throws.
ENV COREJ_DEFINE_RULES_DIR=/app/rules-define

# The external-dictionary store. coreJ ships NO dictionary data — the image
# carries the installer and an empty store; until an operator installs, all
# dictionary rules SKIP loudly by name. Install into a volume so it outlives the
# container:
#   docker run --rm -v corej-dicts:/app/dictionaries <image> --install-dictionaries
# The entrypoint also auto-converts licensed distributions mounted under
# /licensed-dictionaries/<type> and, opt-in via COREJ_DICTIONARY_AUTO_INSTALL=1,
# downloads the credential-free trio — see docker-entrypoint.sh.
# ⚠ Configured-but-missing throws.
ENV COREJ_DICTIONARIES_DIR=/app/dictionaries

# The CDISC Library web-API cache — a DIRECTORY of cached API responses that
# `--seed-cache` fills and every later run reads.
# ⚠⚠ Without this ENV the client's own default applies, ~/.cdiscApiCache, and the
# user created above has --home-dir /app: that resolves to /app/.cdiscApiCache, an
# IMAGE-LAYER path in a container that runs once and exits. A `--seed-cache` run
# would write it and lose it on exit, silently, and every later run would still
# find an empty cache. Seed onto a volume instead:
#   docker run --rm -v corej-api-cache:/app/api-cache <image> --seed-cache
# `-ca` / `--cache` on the command line still outranks this.
ENV CDISC_API_CACHE=/app/api-cache

# /data is the conventional mount point for the study under validation and for the
# report output: CWD-relative inputs/outputs (the default CORE-Report-<ts>.json and
# its runtime CSV) land here, on the caller's bind mount.
RUN mkdir -p /data /app/rules /app/rules-define /app/dictionaries /app/api-cache \
 && chmod +x /app/dist/run.sh /app/docker-entrypoint.sh \
 && chown -R corej:corej /app /data

USER corej
WORKDIR /data

# docker-entrypoint.sh prepares the stores, then execs run.sh, which execs the JVM
# — so java replaces the shell and stays PID 1, and args after the image name flow
# through to CdiscValidate. Bare `docker run <image>` prints help.
ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["--help"]
