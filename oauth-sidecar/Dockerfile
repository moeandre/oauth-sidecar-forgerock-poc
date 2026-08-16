# --- build ---
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# --- runtime ---
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/oauth-sidecar-1.0.0.jar app.jar
EXPOSE 8082 9090

# -XX:MaxRAMPercentage: a JVM ja e "container-aware" por padrao (le o limite
#   de memoria do cgroup), mas o teto default (25% da RAM do container) e
#   conservador demais pra um processo cujo unico trabalho e este - sobe pra
#   75%, deixando margem pra thread stacks/metaspace/etc fora do heap.
# -XX:+ExitOnOutOfMemoryError: em vez da JVM ficar "viva" mas degradada apos
#   um OutOfMemoryError (sintoma classico de memory leak sob carga), derruba
#   o processo na hora - o orquestrador (docker/k8s) reinicia o container
#   limpo, em vez de servir erros silenciosamente por tempo indeterminado.
# JAVA_OPTS como env var (nao hardcoded): permite ajustar por ambiente
#   (ex.: JVM maior em prod) sem rebuildar a imagem.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
