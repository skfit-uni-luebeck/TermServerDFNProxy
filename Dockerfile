FROM azul/zulu-openjdk:17

WORKDIR /
ADD . /app
RUN ./gradlew build


ENTRYPOINT ["java", "-jar", "/build/"]
