package org.sync.reindex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"org.sync"})
public class ReindexJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReindexJobApplication.class, args);
    }
}
