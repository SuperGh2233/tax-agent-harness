package com.taxagent.harness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HarnessApplication {
    public static void main(String[] args) throws Exception {
        if (java.util.Arrays.asList(args).contains("--initialize-data")) {
            var path = "data/harness.db";
            for (var arg : args) {
                if (arg.startsWith("--database=")) path = arg.substring("--database=".length());
                else if (!arg.equals("--initialize-data")) throw new IllegalArgumentException("Unknown initialization argument: " + arg);
            }
            if (path.isBlank()) throw new IllegalArgumentException("Database path must not be blank");
            DataInitializer.initialize(java.nio.file.Path.of(path));
            System.out.println("Initialized (existing records preserved): " + java.nio.file.Path.of(path).toAbsolutePath());
            return;
        }
        SpringApplication.run(HarnessApplication.class, args);
    }
}
