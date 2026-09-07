# CGI vs Servlet -- verification lookup benchmark

Generated 2026-09-07 11:04:33


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
| Python CGI | 375.51 ms | 371.95 ms | 406.87 ms | 432.98 ms | 299.69 ms | 436.94 ms | 2.7 req/s |
| Java Servlet | 2.63 ms | 2.28 ms | 3.42 ms | 6.30 ms | 1.55 ms | 23.62 ms | 373.4 req/s |

**The servlet answers the same lookup 143 times faster.**

## Concurrency sweep

| Concurrent clients | CGI mean | Servlet mean | CGI p95 | Servlet p95 | CGI req/s | Servlet req/s | Speed-up |
|---|---|---|---|---|---|---|---|
| 1 | 384.85 ms | 2.34 ms | 425.36 ms | 3.75 ms | 2.6 | 420.3 | 165x |
| 2 | 361.34 ms | 2.64 ms | 391.36 ms | 3.64 ms | 5.5 | 739.9 | 137x |
| 5 | 388.76 ms | 2.33 ms | 418.00 ms | 4.38 ms | 12.8 | 2046.3 | 167x |
| 10 | 435.44 ms | 3.52 ms | 489.70 ms | 5.73 ms | 22.6 | 2534.0 | 124x |
| 20 | 648.92 ms | 4.31 ms | 726.00 ms | 9.14 ms | 29.9 | 2315.9 | 150x |
| 40 | 1158.10 ms | 3.79 ms | 1645.55 ms | 9.67 ms | 30.7 | 1880.3 | 306x |
