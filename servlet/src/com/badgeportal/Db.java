package com.badgeportal;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small, explicit JDBC connection pool.
 *
 * This class is the concrete embodiment of the point the whole PBL is
 * making. The pool is built ONCE, when the servlet container starts the
 * web application, and every request thread afterwards borrows an
 * already-open connection, uses it for well under a millisecond of
 * index lookup, and hands it straight back.
 *
 * The CGI implementation structurally cannot do this. Each CGI request
 * is a brand-new operating-system process with a brand-new address
 * space: there is nowhere for a pool to live between requests, so
 * cgi/verify.py has to pay the full TCP connect + MySQL handshake +
 * authentication cost every time, and then throw the connection away.
 *
 * The pool is written by hand rather than configured through a Tomcat
 * JNDI DataSource, deliberately: it keeps deployment to a single
 * WAR-shaped folder with no edits to the Tomcat conf/ files, and it
 * makes the borrow/return lifecycle visible in the source the report
 * cites.
 */
public final class Db {

    private static BlockingQueue<Connection> pool;
    private static final List<Connection> allConnections = new ArrayList<Connection>();
    private static volatile boolean started = false;

    // Counters surfaced by /api/health, so the report can show that the
    // servlet really is reusing a fixed set of connections.
    private static final AtomicLong borrowCount    = new AtomicLong();
    private static final AtomicLong reconnectCount = new AtomicLong();

    private Db() { }

    /** Opens poolSize connections up front. Called once, at app startup. */
    public static synchronized void init(int size) throws SQLException {
        if (started) return;

        // Explicit driver load. Modern Connector/J self-registers through
        // the ServiceLoader, but naming it here makes the dependency
        // obvious to a reader and fails loudly if the jar is missing.
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException(
                "MySQL Connector/J not found on the classpath. "
              + "Expected mysql-connector-j-*.jar in WEB-INF/lib.", e);
        }

        pool = new ArrayBlockingQueue<Connection>(size);
        for (int i = 0; i < size; i++) {
            Connection c = open();
            allConnections.add(c);
            pool.add(c);
        }
        started = true;
    }

    private static Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(
                Config.jdbcUrl(), Config.dbUser(), Config.dbPass());
        c.setAutoCommit(true);
        return c;
    }

    /**
     * Takes a connection from the pool, waiting briefly if every one is
     * in use. A connection that has gone stale (MySQL closed it after
     * wait_timeout, say) is quietly replaced.
     */
    public static Connection borrow() throws SQLException {
        if (!started) {
            throw new SQLException("Connection pool not initialised -- "
                                 + "did AppListener.contextInitialized run?");
        }
        Connection c;
        try {
            c = pool.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a connection", e);
        }
        if (c == null) {
            throw new SQLException("Timed out waiting for a pooled connection "
                                 + "(pool size " + Config.poolSize() + ")");
        }
        if (!isUsable(c)) {
            synchronized (allConnections) {
                allConnections.remove(c);
            }
            closeQuietly(c);
            c = open();
            synchronized (allConnections) {
                allConnections.add(c);
            }
            reconnectCount.incrementAndGet();
        }
        borrowCount.incrementAndGet();
        return c;
    }

    /** Returns a connection to the pool. Always call this in a finally block. */
    public static void release(Connection c) {
        if (c == null) return;
        if (!pool.offer(c)) {
            // Should not happen: the queue is sized to the number of
            // connections we created. Close rather than leak.
            closeQuietly(c);
        }
    }

    private static boolean isUsable(Connection c) {
        try {
            return !c.isClosed() && c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            if (c != null) c.close();
        } catch (SQLException ignored) { }
    }

    /** Closes every connection. Called when the container stops the app. */
    public static synchronized void shutdown() {
        started = false;
        if (pool != null) pool.clear();
        synchronized (allConnections) {
            for (Connection c : allConnections) closeQuietly(c);
            allConnections.clear();
        }
    }

    public static boolean isStarted() { return started; }
    public static int     idleCount() { return pool == null ? 0 : pool.size(); }
    public static long    borrows()   { return borrowCount.get(); }
    public static long    reconnects(){ return reconnectCount.get(); }
}
