package com.taxagent.harness.demo;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** React is the single UI; the packaged JAR contains the built assets under /react-app/. */
@Controller
public final class FrontendEntry {
    @GetMapping("/")
    public String root() {
        return "redirect:/react-app/";
    }
    @GetMapping({"/react-app", "/react-app/"})
    public String reactApp() {
        return "forward:/react-app/index.html";
    }
}