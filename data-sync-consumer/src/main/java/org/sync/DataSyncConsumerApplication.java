package org.sync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class DataSyncConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataSyncConsumerApplication.class, args);
    }

    /** 共享 RestTemplate，避免每个组件自行 new */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
