package ua.votivo.poll;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.TimeZone;

@SpringBootApplication
public class PollServiceApplication {
    public static void main(String[] args) {
        // Keep JDBC startup independent of OS timezone aliases; domain timestamps are Instants.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(PollServiceApplication.class, args);
    }
}
