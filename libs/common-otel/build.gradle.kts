plugins { id("java-library") }

dependencies {
  api("io.opentelemetry:opentelemetry-api:1.39.0")
  api("io.opentelemetry:opentelemetry-sdk:1.39.0")
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
