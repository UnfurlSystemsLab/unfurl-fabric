FROM eclipse-temurin:21-jre-jammy

ARG JAR_FILE=target/unfurl-fabric-studio-server.jar

WORKDIR /app
COPY ${JAR_FILE} /app/unfurl-fabric-studio-server.jar

RUN useradd --system --create-home --home-dir /app unfurl \
    && mkdir -p /opt/unfurl/fabric/state /opt/unfurl/fabric/assets \
    && chown -R unfurl:unfurl /app /opt/unfurl

USER unfurl
EXPOSE 7878

ENV JAVA_OPTS="" \
    UNFURL_STUDIO_BIND="0.0.0.0" \
    UNFURL_STUDIO_PORT="7878" \
    UNFURL_STUDIO_STATE_PATH="/opt/unfurl/fabric/state/studio-state.json"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/unfurl-fabric-studio-server.jar --bind ${UNFURL_STUDIO_BIND} --port ${UNFURL_STUDIO_PORT} --state-path ${UNFURL_STUDIO_STATE_PATH}"]
