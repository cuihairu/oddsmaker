# Oddsmaker Load Testing Guide

## Overview

This directory contains load testing scripts and configurations for the Oddsmaker Gaming Analytics Platform.

## Test Scenarios

### 1. Game Management
- Create, Read, Update, Delete games
- Simulates admin operations
- Target: 10 RPS

### 2. Environment Management
- Create and manage game environments
- Simulates DevOps operations
- Target: 5 RPS

### 3. API Key Management
- Create, read, delete API keys
- Simulates developer operations
- Target: 5 RPS

### 4. Experiment Management
- Full experiment lifecycle
- Simulates product manager operations
- Target: 3 RPS

### 5. Health Checks
- Service health monitoring
- Simulates monitoring systems
- Target: 20 RPS

### 6. Read-Heavy Operations
- List and search operations
- Simulates dashboard users
- Target: 50 RPS

## Running Tests

### Prerequisites

1. Java 17+
2. Gradle 8+
3. Running Oddsmaker instance

### Run with Gradle

```bash
# Run load test
./gradlew gatlingRun -Dgatling.simulationClass=tests.load.OddsmakerLoadTest

# Run with custom parameters
./gradlew gatlingRun \
  -Dbase.url=http://localhost:8085 \
  -Dadmin.token=your-admin-token \
  -Dramp.up.seconds=120 \
  -Dtest.duration.seconds=600 \
  -Dtarget.rps=200
```

### Run with Docker

```bash
# Build Gatling image
docker build -t oddsmaker-gatling -f Dockerfile.gatling .

# Run load test
docker run --rm \
  -v $(pwd)/results:/opt/gatling/results \
  -e BASE_URL=http://host.docker.internal:8085 \
  -e ADMIN_TOKEN=your-admin-token \
  oddsmaker-gatling
```

### Run with Gatling Enterprise

1. Upload simulation to Gatling Enterprise
2. Configure injection profile
3. Run test
4. Analyze results

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `base.url` | `http://localhost:8085` | Base URL of the service |
| `admin.token` | `dev-admin-token-...` | Admin authentication token |
| `ramp.up.seconds` | `60` | Ramp-up duration in seconds |
| `test.duration.seconds` | `300` | Test duration in seconds |
| `target.rps` | `100` | Target requests per second |

### Load Profiles

**Light Load:**
```bash
-Dramp.up.seconds=30
-Dtest.duration.seconds=120
-Dtarget.rps=50
```

**Medium Load:**
```bash
-Dramp.up.seconds=60
-Dtest.duration.seconds=300
-Dtarget.rps=100
```

**Heavy Load:**
```bash
-Dramp.up.seconds=120
-Dtest.duration.seconds=600
-Dtarget.rps=200
```

**Stress Test:**
```bash
-Dramp.up.seconds=60
-Dtest.duration.seconds=600
-Dtarget.rps=500
```

## Performance Benchmarks

### Target Metrics

| Metric | Target | Description |
|--------|--------|-------------|
| Response Time (P50) | < 500ms | Median response time |
| Response Time (P95) | < 2000ms | 95th percentile response time |
| Response Time (P99) | < 5000ms | 99th percentile response time |
| Error Rate | < 1% | Percentage of failed requests |
| Throughput | > 100 RPS | Requests per second |

### Expected Performance

**Single Instance (4 CPU, 8GB RAM):**
- Light load: 50 RPS, P95 < 500ms
- Medium load: 100 RPS, P95 < 1000ms
- Heavy load: 200 RPS, P95 < 2000ms

**Cluster (3 instances):**
- Light load: 150 RPS, P95 < 500ms
- Medium load: 300 RPS, P95 < 1000ms
- Heavy load: 600 RPS, P95 < 2000ms

## Analyzing Results

### Gatling Report

After test completion, Gatling generates an HTML report in `results/` directory:

```
results/
├── oddsmakerloadtest-<timestamp>/
│   ├── index.html          # Main report
│   ├── js/                 # JavaScript files
│   └── style/              # CSS files
```

### Key Metrics to Monitor

1. **Response Time Distribution**
   - Check P50, P95, P99 percentiles
   - Look for outliers

2. **Requests per Second**
   - Verify target RPS achieved
   - Check for throttling

3. **Error Rate**
   - Should be < 1%
   - Analyze error types

4. **Response Time Over Time**
   - Look for degradation
   - Check for memory leaks

### Performance Issues

**High Response Time:**
- Check database queries
- Verify connection pool settings
- Monitor CPU/memory usage

**High Error Rate:**
- Check application logs
- Verify database connectivity
- Check resource limits

**Low Throughput:**
- Increase connection pool
- Add more instances
- Optimize code

## Continuous Performance Testing

### CI/CD Integration

Add to `.github/workflows/performance.yaml`:

```yaml
name: Performance Test
on:
  pull_request:
    branches: [main]

jobs:
  performance:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run Load Test
        run: |
          ./gradlew gatlingRun \
            -Dbase.url=${{ secrets.STAGING_URL }} \
            -Dadmin.token=${{ secrets.ADMIN_TOKEN }} \
            -Dramp.up.seconds=30 \
            -Dtest.duration.seconds=120 \
            -Dtarget.rps=50
      - name: Check Performance
        run: |
          # Parse Gatling report and check thresholds
          ./scripts/check-performance.sh
```

### Performance Regression Detection

1. Store baseline metrics
2. Compare with current run
3. Alert on regression
4. Block PR if regression > 10%

## Troubleshooting

### Common Issues

**Connection Refused:**
```bash
# Check if service is running
curl http://localhost:8085/actuator/health

# Check firewall
netstat -tlnp | grep 8085
```

**Timeout Errors:**
```bash
# Increase timeout in Gatling
-Dgatling.http.ahc.requestTimeout=60000

# Check network latency
ping localhost
```

**High Memory Usage:**
```bash
# Increase JVM memory
export JAVA_OPTS="-Xmx4g -Xms4g"

# Check for memory leaks
jmap -histo <pid> | head -20
```

## Best Practices

1. **Start Small**: Begin with light load and gradually increase
2. **Monitor Resources**: Watch CPU, memory, disk, network
3. **Isolate Environment**: Use dedicated test environment
4. **Clean Up**: Delete test data after tests
5. **Document Results**: Save reports for comparison
6. **Automate**: Integrate with CI/CD pipeline

## References

- [Gatling Documentation](https://gatling.io/docs/)
- [Performance Testing Best Practices](https://gatling.io/docs/gatling/tutorials/advanced/)
- [Oddsmaker Architecture](../../docs/zh/reference/architecture.md)
