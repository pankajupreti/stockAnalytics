
package com.example.sheetimport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SheetImportApplication {
    public static void main(String[] args) {
        SpringApplication.run(SheetImportApplication.class, args);
    }
}
