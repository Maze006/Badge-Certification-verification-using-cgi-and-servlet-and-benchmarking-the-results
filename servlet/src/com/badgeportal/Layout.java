package com.badgeportal;

/**
 * The page chrome every server-rendered page shares: the seal, the
 * title, the nav, and the footer.
 *
 * Extracted so that the wallet, the dashboards and the login page cannot
 * drift apart visually. Previously WalletServlet carried its own private
 * copy, which was fine while it was the only server-rendered page and
 * would not have survived four more.
 *
 * The static pages (index.html, verify.html, health.html) hold the same
 * markup by hand. That duplication is deliberate: making them dynamic
 * purely to share a header would mean routing static content through a
 * servlet for no benefit.
 */
public final class Layout {

    private Layout() { }

    /**
     * @param activeTab which nav tab to underline: "home", "wallets",
     *                  "verify", "health", or "" for none.
     * @param ctx       the context path, from request.getContextPath().
     *
     * Every URL here is built from ctx rather than written relative.
     * That is not cosmetic. The wallet lives at /badgeportal/wallet but
     * the dashboards live at /badgeportal/student/dashboard, one segment
     * deeper, so a relative "css/style.css" resolves differently on the
     * two pages and 404s on one of them. Absolute-from-context URLs
     * resolve identically no matter how deep the page sits.
     */
    public static String head(String title, String activeTab, String ctx) {
        return head(title, activeTab, ctx, null);
    }

    /**
     * @param account the signed-in account, or null.
     *
     * The first nav tab depends on who is looking. For a visitor it is
     * "home", the landing page. For somebody signed in it is
     * "dashboard": sending an authenticated user back to a
     * tap-anywhere-to-begin splash screen would be absurd.
     */
    public static String head(String title, String activeTab, String ctx,
                              Accounts.Account account) {
        boolean signedIn = account != null;

        StringBuilder sb = new StringBuilder(1000);
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>")
          .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
          .append("<title>").append(Json.html(title)).append("</title>")
          .append("<link rel='stylesheet' href='").append(ctx)
          .append("/css/style.css'>")
          .append("</head><body>")
          .append("<header class='site-header'>")
          .append("<div class='seal' aria-hidden='true'>&#10003;</div><div>")
          .append("<a class='brand' href='").append(ctx)
          .append(signedIn ? Auth.homeFor(account) : "/index.html")
          .append("'>Verified skill-badge portal</a>")
          .append("<p class='tagline'>Micro-credentials issued with a ")
          .append("tamper-evident verification code</p>")
          .append("</div></header>")
          .append("<nav class='nav-tabs'><ul>");

        if (signedIn) {
            sb.append(tab(ctx + Auth.homeFor(account), "dashboard", activeTab));
        } else {
            sb.append(tab(ctx + "/index.html", "home", activeTab));
        }

        sb.append(tab(ctx + "/wallet",         "wallets",   activeTab))
          .append(tab(ctx + "/verify.html",    "verify",    activeTab))
          .append(tab(ctx + "/benchmark.html", "benchmark", activeTab))
          .append(tab(ctx + "/health.html",    "health",    activeTab))
          .append("</ul></nav>");
        return sb.toString();
    }

    /** Page chrome plus the signed-in bar. */
    public static String headSignedIn(String title, String activeTab,
                                      Accounts.Account account, String ctx) {
        StringBuilder sb = new StringBuilder(1400);
        sb.append(head(title, activeTab, ctx, account));
        if (account != null) {
            sb.append("<div class='session-bar'><div class='session-inner'>")
              .append("<span>Signed in as <strong>")
              .append(Json.html(account.displayName))
              .append("</strong> <span class='role-chip'>")
              .append(Json.html(account.role))
              .append("</span></span>")
              .append("<a class='btn small ghost' href='").append(ctx)
              .append("/logout'>Sign out</a>")
              .append("</div></div>");
        }
        return sb.toString();
    }

    public static String foot() {
        return "<footer class='site-foot'>Served by the "
             + "<strong>servlet</strong> implementation on Apache Tomcat"
             + "</footer></body></html>";
    }

    private static String tab(String href, String label, String activeTab) {
        boolean active = label.equals(activeTab);
        return "<li><a href='" + href + "'"
             + (active ? " class='is-active' aria-current='page'" : "")
             + ">" + label + "</a></li>";
    }
}
