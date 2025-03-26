package myorg;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;
import software.amazon.awscdk.services.lex.CfnBot;

import java.util.List;
import java.util.Map;

public class CustomerSupportBotStack extends Stack {
    public CustomerSupportBotStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);
        ILayerVersion layer = LayerVersion.fromLayerVersionArn(this, "BotDependencies", "arn:aws:lambda:us-east-1:960673175457:layer:BotDependencies:7");
        ITable Clients_Database = Table.fromTableName(this, "Clients_Database", "Clients_Database");

        Role lambdaRole = Role.Builder.create(this, "LambdaExecutionRole")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonLexFullAccess")
                ))
                .build();

        Clients_Database.grantFullAccess(lambdaRole);

        Function SimpleHandler = Function.Builder.create(this, "SimpleHandler")
                .runtime(Runtime.JAVA_17)
                .handler("myorg.SimpleHandler::handleRequest")
                .code(Code.fromAsset("target/customer-support-bot-0.1.jar"))
                .memorySize(2048)
                .timeout(Duration.seconds(300))
                .role(lambdaRole)
                .layers(List.of(layer))
                .environment(Map.of(
                        "DYNAMODB_TABLE_NAME", Clients_Database.getTableName()
                ))
                .build();
        SimpleHandler.addPermission("LexInvokePermission",
                Permission.builder()
                        .principal(new ServicePrincipal("lex.amazonaws.com"))
                        .action("lambda:InvokeFunction")
                        .sourceArn("arn:aws:lex:us-east-1:960673175457:bot-alias/YJPJLY5XGP/TSTALIASID")
                        .build()
        );

        CfnBot.SlotProperty orderNumberSlot = CfnBot.SlotProperty.builder()
                .name("OrderNumber")
                .description("Order number for reference")
                .slotTypeName("AMAZON.Number")
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Required")
                        .promptSpecification(createPromptSpecification("Please provide your order number."))
                        .build())
                .build();

        CfnBot.SlotTypeProperty actionSlotType = CfnBot.SlotTypeProperty.builder()
                .name("ActionType")
                .description("Defines actions the user can take on an order")
                .slotTypeValues(List.of(
                        CfnBot.SlotTypeValueProperty.builder()
                                .sampleValue(CfnBot.SampleValueProperty.builder().value("update shipping address").build())
                                .synonyms(List.of(
                                        CfnBot.SampleValueProperty.builder().value("update delivery address").build()
                                ))
                                .build(),
                        CfnBot.SlotTypeValueProperty.builder()
                                .sampleValue(CfnBot.SampleValueProperty.builder().value("cancel").build())
                                .synonyms(List.of(
                                        CfnBot.SampleValueProperty.builder().value("delete").build()
                                ))
                                .build()
                ))
                .valueSelectionSetting(CfnBot.SlotValueSelectionSettingProperty.builder()
                        .resolutionStrategy("TOP_RESOLUTION")
                        .build())
                .build();

        CfnBot.SlotProperty actionTypeSlot = CfnBot.SlotProperty.builder()
                .name("ActionType")
                .description("Action to perform")
                .slotTypeName(actionSlotType.getName())
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Required")
                        .promptSpecification(createPromptSpecification("How would you like to change you order ?"))
                        .build())
                .build();

        CfnBot.SlotProperty shippingAddressSlot = CfnBot.SlotProperty.builder()
                .name("ShippingAddress")
                .description("New shipping address")
                .slotTypeName("AMAZON.City")
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Optional")
                        .promptSpecification(createPromptSpecification("Please provide the new shipping address."))
                        .build())
                .build();

        CfnBot.BotLocaleProperty botLocale = CfnBot.BotLocaleProperty.builder()
                .localeId("en_US")
                .nluConfidenceThreshold(0.8)
                .slotTypes(List.of(actionSlotType))
                .intents(List.of(
                        createChangeOrderIntent(orderNumberSlot,actionTypeSlot,shippingAddressSlot),
                        createFallbackIntent()
                ))
                .voiceSettings(CfnBot.VoiceSettingsProperty.builder()
                        .voiceId("Joanna")
                        .build())
                .build();

        CfnBot lexBot = CfnBot.Builder.create(this, "CustomerSupportBot")
                .name("CustomerSupportBot")
                .dataPrivacy(Map.of("ChildDirected", false))
                .idleSessionTtlInSeconds(300)
                .roleArn(lambdaRole.getRoleArn())
                .botLocales(List.of(botLocale))
                .build();

    }

    private CfnBot.IntentProperty createChangeOrderIntent(CfnBot.SlotProperty orderNumberSlot,CfnBot.SlotProperty actionTypeSlot, CfnBot.SlotProperty shippingAddressSlot) {
        return CfnBot.IntentProperty.builder()
                .name("ChangeOrderIntent")
                .description("Acts as a router for order modifications")
                .slots(List.of(orderNumberSlot,actionTypeSlot,shippingAddressSlot))
                .slotPriorities(List.of(
                        CfnBot.SlotPriorityProperty.builder().priority(1).slotName("OrderNumber").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(2).slotName("ActionType").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(3).slotName("ShippingAddress").build()
                ))
                .sampleUtterances(List.of(
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want to change my order").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Modify order {OrderNumber}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Update my order {OrderNumber}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Change the shipping address for order {OrderNumber}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want to update the shipping address for order {OrderNumber} to {ShippingAddress}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Modify order {OrderNumber} shipping address").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want to {ActionType} with number {OrderNumber}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want to {ActionType} my order").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I need to {ActionType} my order").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("help me with {ActionType} my order").build()

                        ))
                .dialogCodeHook(CfnBot.DialogCodeHookSettingProperty.builder()
                        .enabled(true)
                        .build())
                .fulfillmentCodeHook(CfnBot.FulfillmentCodeHookSettingProperty.builder()
                        .enabled(true)
                        .build())
                .build();
    }

    private CfnBot.IntentProperty createFallbackIntent() {
        return CfnBot.IntentProperty.builder()
                .name("FallbackIntent")
                .description("Handles unknown user inputs")
                .parentIntentSignature("AMAZON.FallbackIntent")
                .build();
    }

    private CfnBot.PromptSpecificationProperty createPromptSpecification(String message) {
        return CfnBot.PromptSpecificationProperty.builder()
                .messageGroupsList(List.of(
                        CfnBot.MessageGroupProperty.builder()
                                .message(
                                        CfnBot.MessageProperty.builder()
                                                .plainTextMessage(
                                                        CfnBot.PlainTextMessageProperty.builder()
                                                                .value(message)
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                ))
                .maxRetries(2)
                .allowInterrupt(true)
                .build();
    }
}
