package com.taxagent.harness.demo;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Prefer the built React workspace when it is packaged; otherwise keep the self-contained static page.
 * This is the switch-over point for converging the two front ends without breaking source-only checkouts.
 */
@Controller
public final class FrontendEntry {
    @GetMapping("/")
    public String root() {
        return new ClassPathResource("static/react-app/index.html").exists()
                ? "redirect:/react-app/" : "forward:/index.html";
    }
    @GetMapping({"/react-app", "/react-app/"})
    public String reactApp() {
        return "forward:/react-app/index.html";
    }
}