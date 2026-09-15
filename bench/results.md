# CGI vs Servlet -- verification lookup benchmark

Generated 2026-09-16 00:27:57


| | |
|---|---|
| Servlet | Apache Tomcat/8.5.96 |
| Servlet JVM | Java 1.8.0_401 |
| Connection pool | 16 connections, opened once at startup |
| CGI | Apache HTTP Server + mod_cgi, Python 3.13.4 |
| Database | MySQL, 336 badges in the table |
| Requests per phase | 100 |

## Sequential (one client at a time)

| Implementation | Mean | Median | p95 | p99 | Min | Max | Throughput |
|---|---|---|---|---|---|---|---|
| Python CGI | 395.88 ms | 396.52 ms | 411.19 ms | 417.59 ms | 292.44 ms | 418.63 ms | 2.5 req/s |
| Java Servlet | 1.47 ms | 1.19 ms | 2.09 ms | 5.41 ms | 0.83 ms | 20.83 ms | 666.0 req/s |

**The servlet answers the same lookup 269 times faster.**

## Concurrency sweep

| Concurrent clients | CGI mean | Servlet mean | CGI p95 | Servlet p95 | CGI req/s | Servlet req/s | Speed-up |
|---|---|---|---|---|---|---|---|
| 1 | 394.84 ms | 1.37 ms | 409.99 ms | 1.79 ms | 2.5 | 713.3 | 287x |
| 2 | 395.20 ms | 1.49 ms | 412.82 ms | 1.78 ms | 5.1 | 1308.5 | 266x |
| 5 | 427.43 ms | 1.77 ms | 462.54 ms | 2.18 ms | 11.6 | 2645.3 | 241x |
| 10 | 482.44 ms | 2.67 ms | 593.37 ms | 4.73 ms | 20.1 | 3240.3 | 181x |
| 20 | 763.72 ms | 4.78 ms | 982.56 ms | 11.30 ms | 24.6 | 1789.7 | 160x |
| 40 | 2590.89 ms | 4.66 ms | 4066.65 ms | 9.37 ms | 12.7 | 1315.6 | 556x |
