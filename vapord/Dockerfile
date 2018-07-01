# Builds vapord using Docker
# Usage: docker build -t vapord .

FROM openjdk:8-jdk-alpine

ARG SBT_VERSION=1.1.6

# Dependencies
RUN \
  apk add --no-cache \
    bash \
    git && \
  mkdir -p /opt && \
  wget -q -O - https://sbt-downloads.cdnedge.bluemix.net/releases/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz | \
  gunzip | \
  tar -x -C /opt
ENV PATH="/opt/sbt/bin:${PATH}"

# Build user
RUN adduser -D build -s 1000
USER build:build
WORKDIR /home/build

# Build project
COPY --chown=build:build . /home/build/
RUN \
  sbt assembly

# Application image
FROM openjdk:8-jre-alpine
RUN \
  apk add --no-cache dumb-init && \
  adduser -h /data -u 1000 -D app
COPY --from=0 /home/build/target/scala-*/vapord-*.jar /opt/vapord.jar
WORKDIR /data
VOLUME /data
USER app
ENTRYPOINT ["/usr/bin/dumb-init", "--", "/bin/sh", "-c", "exec /usr/bin/java $JAVA_OPTS -jar /opt/vapord.jar \"$*\""]

