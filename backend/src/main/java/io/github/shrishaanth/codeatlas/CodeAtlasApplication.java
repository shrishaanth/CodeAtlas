package io.github.shrishaanth.codeatlas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class CodeAtlasApplication {

    public static void main(String[] args) {
        // All stored times are instants, so the server's zone never matters, except that the
        // Postgres driver sends it on connect and Postgres 17 rejects legacy names such as
        // "Asia/Calcutta". Running in UTC avoids that on every developer machine.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(CodeAtlasApplication.class, args);
    }
}
