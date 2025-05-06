package myorg;

import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Stage;
import software.amazon.awscdk.StageProps;
import software.constructs.Construct;

public class CustomerSupportBotStage extends Stage {
    public CustomerSupportBotStage(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public CustomerSupportBotStage(final Construct scope, final String id, final StageProps props) {
        super(scope, id, props);

        // Add the CustomerSupportBotStack to this stage
        new CustomerSupportBotStack(this, "CustomerSupportBotStack", StackProps.builder()
                .env(props != null ? props.getEnv() : null)
                .build());
    }
}