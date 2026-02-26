package org.openphc.cce.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CollectorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollectorServiceApplication.class, args);
    }
}
