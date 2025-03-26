package myorg;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class CustomerSupportBotApp {
    public static void main(final String[] args) {
        App app = new App();
        Environment environment = Environment.builder()
                .account("960673175457")
                .region("us-east-1")
                .build();

        new CustomerSupportBotStack(app, "CustomerSupportBotStack", StackProps.builder()
                .env(environment)
                .build());
        app.synth();
    }
}

