package labs.engineeringproof.policylab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PolicyLabApplication {
    public static void main(String[] args) {
        var application = SpringApplication.run(PolicyLabApplication.class, args);
        if (application.getEnvironment().matchesProfiles("retrieval-eval")) {
            application.close();
        }
    }
}
