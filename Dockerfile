# Base image pinned by digest for reproducible, supply-chain-safe builds. The tag is kept for
# human readability; the digest is what actually pins it. Update both deliberately when bumping
# the base image (re-resolve with: docker buildx imagetools inspect eclipse-temurin:17-jre).
FROM eclipse-temurin:17-jre@sha256:0d79988c68791ce864fe39d149ab1dc84f680539dca77ee7f6f3b041ad7f2f43

WORKDIR /app

# The shaded jar is built by `mvn package` before the image build (see .github/workflows and
# docker-compose). Copying a prebuilt artifact keeps the runtime image to a JRE only (no build
# toolchain, no source) for a smaller attack surface.
COPY target/protogemcouch.jar /app/protogemcouch.jar

# Run as an unprivileged, fixed non-root UID/GID. The jar is world-readable and the process only
# reads it (writing temp to /tmp), so no chown is needed; this also works under read-only root
# filesystems and arbitrary-UID admission policies.
USER 10001:10001

EXPOSE 40405
EXPOSE 8081

# Bound the heap to a share of the container's memory limit (cgroup-aware) rather than the JVM default
# of 25% of *host* RAM — without this, a shim run without a container memory limit (e.g. plain
# docker-compose) lets the heap balloon to gigabytes, and one with a limit under-uses it. 75% leaves
# headroom for Netty direct buffers, metaspace, and thread stacks. Operators set the actual ceiling via
# the container memory limit (the Helm chart's resources.limits.memory; docker-compose mem_limit).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/protogemcouch.jar"]
