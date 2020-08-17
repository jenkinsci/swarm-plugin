# Prometheus monitoring

The Jenkins Swarm Client has support for [Prometheus](https://prometheus.io) monitoring, which can be used to scrape
data from a Prometheus server. To start a Prometheus endpoint, simply use a non-zero value for the `-prometheusPort`
option when starting the client JAR. The service will be stopped when the Swarm Client exits.

The actual metrics can be accessed on the `/prometheus` endpoint. So for example, if the node's IP address is
`169.254.10.12`, and `9100` is passed to `-prometheusPort`, then the metrics can be accessed at:
`http://169.254.10.12:9100/prometheus`.

## Data Reported

The client reports metrics for:

- Basic process info, including:
  - Process uptime
  - CPU time consumed
  - Virtual memory consumed
  - Resident memory consumed
  - File descriptors consumed
- JVM metrics such as:
  - CPU usage
  - Memory usage
  - Thread states
  - Garbage collection statistics
  - Class loader statistics
