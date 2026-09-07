package com.badgeportal;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * GET /badgeportal/api/health
 *
 * Small operational endpoint, used by bench/benchmark.py to confirm the
 * servlet is up before timing anything, and useful in the report as
 * evidence that the connection pool really does persist between
 * requests: hit it a few times and watch "borrows" climb while
 * "poolSize" stays put and "reconnects" stays at zero.
 */
public class HealthServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().print(new Json()
            .put("status", Db.isStarted() ? "UP" : "DOWN")
            .put("implementation", "servlet")
            .put("configSource", Config.configSource())
            .put("poolSize", Config.poolSize())
            .put("idleConnections", Db.idleCount())
            .put("borrows", Db.borrows())
            .put("reconnects", Db.reconnects())
            .put("jvm", System.getProperty("java.version"))
            .put("server", getServletContext().getServerInfo())
            .end());
    }
}
