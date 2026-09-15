package com.badgeportal;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Ends a session.
 *
 *     GET /logout
 *
 * Accepts GET because it is reached from a plain link in the header.
 * That is a small, deliberate compromise: strictly, anything with a side
 * effect should be a POST, and signing out via GET means a stray image
 * tag pointing here could log somebody out. The worst outcome is an
 * unwanted sign-out -- no data is disclosed or destroyed -- so a link is
 * worth the simplicity here, where it would not be for the approve and
 * reject actions in Phase 5.
 */
public class LogoutServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        Auth.signOut(req);
        resp.setHeader("Cache-Control", "no-store");
        resp.sendRedirect(req.getContextPath() + "/index.html");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        doGet(req, resp);
    }
}
