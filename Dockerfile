FROM azul/zulu-openjdk:17

WORKDIR /app
ADD . /app
RUN ./gradlew clean jar

RUN ls /app/build/libs
ENTRYPOINT ["java", "-jar", "/app/build/libs/TermServerDFNProxy-1.0.0.jar", "--config", "/proxy.conf"]
