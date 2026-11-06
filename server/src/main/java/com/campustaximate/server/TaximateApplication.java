package com.campustaximate.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TaximateApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaximateApplication.class, args);
    }
}
