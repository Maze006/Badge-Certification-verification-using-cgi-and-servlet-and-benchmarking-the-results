# CGI vs Servlet -- verification lookup benchmark

Generated 2026-09-16 09:41:38


| | |
|---|---|
| Servlet | Apache Tomcat/8.5.96 |
| Servlet JVM | Java 1.8.0_401 |
| Connection pool | 16 connections, opened once at startup |
| CGI | Apache HTTP Server + mod_cgi, Python 3.13.4 |
| Database | MySQL, 337 badges in the table |
| Requests per phase | 3 |

## Sequential (one client at a time)

| Implementation | Mean | Median | p95 | p99 | Min | Max | Throughput |
|---|---|---|---|---|---|---|---|
| Python CGI | 391.46 ms | 427.55 ms | 434.55 ms | 434.55 ms | 312.28 ms | 434.55 ms | 2.6 req/s |
| Java Servlet | 4.30 ms | 4.84 ms | 4.96 ms | 4.96 ms | 3.10 ms | 4.96 ms | 220.8 req/s |

**The servlet answers the same lookup 91 times faster.**

## Concurrency sweep

| Concurrent clients | CGI mean | Servlet mean | CGI p95 | Servlet p95 | CGI req/s | Servlet req/s | Speed-up |
|---|---|---|---|---|---|---|---|
| 1 | 390.82 ms | 4.15 ms | 420.56 ms | 6.39 ms | 2.6 | 227.4 | 94x |
