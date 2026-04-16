plugins {
    java
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.5"
}

group   = "com.dgm"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// ── Dependency versions outside the Spring BOM ────────────────────────────────
val auth0JwtVersion  = "4.4.0"
val stripeVersion    = "25.1.0"
val twilioVersion    = "9.14.0"
val springdocVersion = "2.5.0"

repositories {
    mavenCentral()
}

dependencies {

    // ── Spring Boot starters ──────────────────────────────────────────────────
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // ── JWT — Supabase signs tokens with HS256 ────────────────────────────────
    implementation("com.auth0:java-jwt:$auth0JwtVersion")

    // ── Stripe payment links and webhook verification ─────────────────────────
    implementation("com.stripe:stripe-java:$stripeVersion")

    // ── Twilio SMS ────────────────────────────────────────────────────────────
    implementation("com.twilio.sdk:twilio:$twilioVersion")

    // ── OpenAPI / Swagger UI ──────────────────────────────────────────────────
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")

    // ── Jackson (pulled in by spring-web; explicit for clarity) ──────────────
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // ── Lombok ────────────────────────────────────────────────────────────────
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // ── Test ──────────────────────────────────────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Single executable fat jar — output: build/libs/dgm-service-api-1.0.0.jar
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("dgm-service-api-${version}.jar")
}

// Disable the thin jar — only the Spring Boot fat jar is needed
tasks.named<Jar>("jar") {
    enabled = false
}
