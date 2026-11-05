# Build stage: compile the server with a full JDK.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY DakBoard.java ./
RUN javac --release 11 DakBoard.java

# Runtime stage: only the compiled server and the bundled assets.
FROM eclipse-temurin:17-jre
RUN groupadd --system --gid 10001 eventboard \
 && useradd --system --uid 10001 --gid eventboard --home-dir /app --shell /usr/sbin/nologin eventboard \
 && mkdir -p /data \
 && chown eventboard:eventboard /data
WORKDIR /app
COPY --from=build /src/*.class ./
COPY admin.html board.html clients.html editor.html login.html room.html schedule.html slides.html ./
COPY sample-events.ics settings.default.json ./
ENV EVENT_BOARD_DATA_DIR=/data \
    EVENT_BOARD_BIND=0.0.0.0
USER eventboard
EXPOSE 8080
ENTRYPOINT ["java", "DakBoard", "8080"]
