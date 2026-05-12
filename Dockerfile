FROM eclipse-temurin:17-jdk

WORKDIR /app

RUN apt-get update && apt-get install -y ghostscript

COPY . .

RUN chmod +x mvnw

RUN ./mvnw clean package -DskipTests

EXPOSE 8080

CMD ["java","-jar","target/aiphotocompressorbackend-0.0.1-SNAPSHOT.jar"]