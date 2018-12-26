
FROM openjdk:8-jre-alpine
RUN \
  apk add --no-cache dumb-init && \
  adduser -h /data -u 1000 -D app
COPY ./backend/target/scala-*/vapor-*.jar /opt/vapor.jar
WORKDIR /data
VOLUME /data
USER app
ENTRYPOINT ["/usr/bin/dumb-init", "--", "/bin/sh", "-c", "exec /usr/bin/java $JAVA_OPTS -jar /opt/vapor.jar \"$@\"", "--"]

