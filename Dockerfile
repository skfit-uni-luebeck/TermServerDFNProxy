FROM azul/zulu-openjdk-alpine:17
WORKDIR /app
ADD . /app
RUN ./gradlew clean jar

ENTRYPOINT ["java", "-jar", "/app/build/libs/TermServerDFNProxy-1.1.0.jar", "--config", "/proxy.conf"]
