package com.mal2cy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Mal2cySyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(Mal2cySyncApplication.class, args);
    }

}