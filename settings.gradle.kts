rootProject.name = "oddsmaker"

include(
  "libs:common-model",
  "libs:common-auth",
  "libs:common-kafka",
  "libs:common-otel",
  "services:gateway-service",
  "services:control-service",
  "jobs:flink:events-enrich-job",
  "jobs:flink:sessions-job"
  ,"jobs:flink:retention-job"
  ,"jobs:flink:funnels-job"
)
