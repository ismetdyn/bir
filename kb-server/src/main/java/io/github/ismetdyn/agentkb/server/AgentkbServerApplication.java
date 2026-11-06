package io.github.ismetdyn.agentkb.server;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentkbServerApplication {

    public static void main(String[] args) {
        // ADR-0022 decision 3: init runs before Spring, so it never opens the graph.
        if (args.length > 0 && InitCommand.NAME.equals(args[0])) {
            System.exit(
                    InitCommand.fromEnvironment(System.getenv())
                            .run(Arrays.copyOfRange(args, 1, args.length), System.out, System.err));
        }
        SpringApplication.run(AgentkbServerApplication.class, args);
    }
}
