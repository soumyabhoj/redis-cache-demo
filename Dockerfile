# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# Copy pom first — Docker layer cache skips dependency download
# if only source code changed
COPY pom.xml ./
RUN mvn dependency:go-offline -q --no-transfer-progress 2>/dev/null || true

COPY src ./src
RUN mvn clean package -DskipTests -q --no-transfer-progress

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/target/redis-cache-demo-1.0.0.jar app.jar

EXPOSE 8080

ENV JAVA_OPTS="-Xms128m -Xmx256m"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
