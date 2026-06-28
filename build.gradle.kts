plugins {
	java
	id("org.springframework.boot") version "3.4.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.scaffoldkit"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
	implementation("me.paulschwarz:spring-dotenv:4.0.0")
	runtimeOnly("com.h2database:h2")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// ── Standalone Betfair Odds Snapshot JAR ───────────────────────────────────
// Produces a runnable fat JAR (build/libs/fifa-0.0.1-SNAPSHOT-standalone.jar)
// that can be executed with: java -jar fifa-...-standalone.jar
//
// The main class is BetfairOddsSnapshotApp, which runs without Spring Boot.
tasks.register<Jar>("standaloneJar") {
	group = "Build"
	description = "Creates a runnable fat JAR for the standalone Betfair odds snapshot app"

	archiveClassifier.set("standalone")

	// Include compiled classes and resources from the main source set
	from(sourceSets.main.get().output)

	// Bundle all runtime dependencies (implementation + runtimeOnly)
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	from({
		configurations.runtimeClasspath.get()
			.filter { it.name.endsWith("jar") }
			.map { zipTree(it) }
	})

	// Exclude signature files from signed third-party JARs to avoid
	// SecurityException when running the fat JAR
	exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

	manifest {
		attributes(
			"Main-Class" to "dev.scaffoldkit.fifa.betfair.BetfairOddsSnapshotApp"
		)
	}
}

// Make standaloneJar part of the default build
tasks.named("build") {
	dependsOn("standaloneJar")
}
