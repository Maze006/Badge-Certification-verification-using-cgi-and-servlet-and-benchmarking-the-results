package com.badgeportal;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Access control for the dashboards.
 *
 * A Filter rather than a check at the top of every servlet, because
 * access control that has to be remembered in each handler is access
 * control that will eventually be forgotten in one of them.
 *
 * ---------------------------------------------------------------------
 *  WHY THIS FILTER IS MAPPED TO /admin/* AND /student/* AND NOT TO /*
 * ---------------------------------------------------------------------
 *  The obvious design is to map a filter at /* and maintain a list of
 *  public paths to skip. That would be a mistake here, for a reason
 *  specific to this project.
 *
 *  /api/verify is the benchmarked endpoint. Its twin, cgi/verify.py,
 *  has no concept of a session and never will -- CGI processes do not
 *  outlive their request. If authentication ever applied to the servlet
 *  side, the two implementations would stop returning identical JSON,
 *  bench/benchmark.py would abort at its parity check, and the central
 *  result of this project would be unreproducible.
 *
 *  Mapping the filter only to the two protected prefixes means the
 *  verification endpoint is not merely excluded from the rules, it is
 *  outside their reach. An exclusion list can be got wrong by editing
 *  one line; this cannot.
 *
 *  It is also correct on its own terms. Verification is public by
 *  design: an employer checking a badge has no account and should
 *  never need one.
 * ---------------------------------------------------------------------
 */
public class AuthFilter implements Filter {

    @Override
    public void init(FilterConfig config) { }

    @Override
    public void destroy() { }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest  req  = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String path = req.getRequestURI().substring(req.getContextPath().length());
        Accounts.Account account = Auth.current(req);

        // Not signed in: send them to the right door, remembering where
        // they were trying to go.
        if (account == null) {
            String role = path.startsWith("/admin") ? "admin" : "student";
            resp.setHeader("Cache-Control", "no-store");
            resp.sendRedirect(req.getContextPath() + "/login?role=" + role);
            return;
        }

        // Role gate, but ONLY for the paths that actually declare a role.
        //
        // /admin/* is for admins and /student/* is for students. Other
        // guarded paths -- /files/* today -- are legitimately reachable
        // by both, and the servlet behind them decides what this
        // particular person may see. An earlier version tested
        // "path.startsWith("/admin") != account.isAdmin()", which
        // quietly 403'd admins on every non-/admin guarded path,
        // because a path with no role prefix reads as "wants student".
        //
        // Requiring a role prefix before enforcing one keeps the rule
        // honest as more guarded paths are added.
        boolean adminArea   = path.startsWith("/admin");
        boolean studentArea = path.startsWith("/student");

        if ((adminArea && !account.isAdmin())
         || (studentArea && !account.isStudent())) {
            deny(req, resp, account);
            return;
        }

        // Dashboards show personal data; keep them out of shared caches
        // and out of the back button after sign-out.
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        resp.setHeader("Pragma", "no-cache");

        chain.doFilter(request, response);
    }

    private void deny(HttpServletRequest req, HttpServletResponse resp,
                      Accounts.Account account) throws IOException {
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        resp.setContentType("text/html;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");

        String ctx = req.getContextPath();
        resp.getWriter().print(
            Layout.headSignedIn("Not permitted", "", account, ctx)
          + "<main class='wrap narrow'><div class='card empty'>"
          + "<h2>Not permitted</h2>"
          + "<p>This area is for "
          + (account.isAdmin() ? "students" : "administrators")
          + ", and you are signed in as "
          + (account.isAdmin() ? "an administrator" : "a student")
          + ".</p>"
          + "<p><a class='btn' href='" + ctx + Auth.homeFor(account)
          + "'>Go to your dashboard</a></p>"
          + "</div></main>" + Layout.foot());
    }
}
