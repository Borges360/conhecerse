package com.psicocare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PlatformApplication {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("migrate")) {
            org.flywaydb.core.Flyway.configure()
                    .dataSource(
                            System.getenv("DB_URL"),
                            System.getenv("MIGRATOR_USER"),
                            System.getenv("MIGRATOR_PASSWORD"))
                    .defaultSchema("app")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            return;
        }
        SpringApplication.run(PlatformApplication.class, args);
    }
}
