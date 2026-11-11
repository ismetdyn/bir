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
                    InitCommand.fromEnvironment(AgentkbPaths.ofThisMachine())
                            .run(Arrays.copyOfRange(args, 1, args.length), System.out, System.err));
        }
        application(AgentkbPaths.ofThisMachine()).run(args);
    }

    /**
     * The server with the default paths of {@code paths} (ADR-0027 decision 1) as its
     * lowest-priority properties: application.yml, the environment and the command line override
     * them.
     */
    static SpringApplication application(AgentkbPaths paths) {
        SpringApplication application = new SpringApplication(AgentkbServerApplication.class);
        application.setDefaultProperties(paths.serverDefaults());
        return application;
    }
}
