package org.sync.bootstrap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class BookingWideReindexRunner implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        log.info("[ES] booking_wide reindex has been moved to reindex-job module.");
    }
}
