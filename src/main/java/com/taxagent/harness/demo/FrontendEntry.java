package com.taxagent.harness.demo;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** React is the single UI; the packaged JAR contains the built assets under /react-app/. */
@Controller
public final class FrontendEntry {
    @GetMapping("/")
    public String root(HttpServletResponse response) {
        noStore(response);
        return "redirect:/react-app/";
    }
    @GetMapping({"/react-app", "/react-app/"})
    public String reactApp(HttpServletResponse response) {
        noStore(response);
        return "forward:/react-app/index.html";
    }
    /** The HTML shell must never be reused from cache; hashed asset names handle asset freshness. */
    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
    }
}