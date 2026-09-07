package com.badgeportal;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.io.InputStream;

/**
 * Application lifecycle hook.
 *
 * Everything expensive happens HERE -- once, when Tomcat starts the web
 * application -- and not on the request path:
 *
 *   * reading the configuration file
 *   * loading the JDBC driver
 *   * opening the connection pool
 *
 * That is the whole servlet argument in miniature. A CGI script has no
 * equivalent hook, because a CGI process does not outlive the request
 * that created it; cgi/verify.py must redo all three steps, every time.
 *
 * Registered in WEB-INF/web.xml rather than by annotation, to keep the
 * whole deployment descriptor readable in one place.
 */
public class AppListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        InputStream in = sce.getServletContext()
                            .getResourceAsStream("/WEB-INF/badgeportal.properties");
        Config.load(in);
        sce.getServletContext().log("[badgeportal] config from " + Config.configSource());

        try {
            long t0 = System.nanoTime();
            Db.init(Config.poolSize());
            long ms = (System.nanoTime() - t0) / 1000000L;
            sce.getServletContext().log(
                "[badgeportal] opened " + Config.poolSize()
              + " pooled JDBC connections in " + ms + " ms -- "
              + "this cost is paid once, not per request");
        } catch (Exception e) {
            sce.getServletContext().log(
                "[badgeportal] FAILED to start connection pool: " + e.getMessage(), e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        Db.shutdown();
        sce.getServletContext().log("[badgeportal] connection pool closed");
    }
}
