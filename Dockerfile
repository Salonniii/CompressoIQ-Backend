FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY . .

RUN chmod +x mvnw

RUN ./mvnw clean install -DskipTests

EXPOSE 8080

CMD ["java","-jar","target/aiphotocompressorbackend-0.0.1-SNAPSHOT.jar"]