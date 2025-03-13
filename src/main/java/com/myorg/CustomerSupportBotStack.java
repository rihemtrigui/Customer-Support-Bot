package com.myorg;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;
import software.amazon.awscdk.services.lex.CfnBot;
import software.amazon.awscdk.services.lex.CfnBot.PromptSpecificationProperty;
import software.amazon.awscdk.services.lex.CfnBotAlias;
import software.amazon.awscdk.services.lex.CfnBot.BotAliasLocaleSettingsProperty;


import java.util.List;
import java.util.Map;

public class CustomerSupportBotStack extends Stack {
    public CustomerSupportBotStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        ITable Clients_Database = Table.fromTableName(this, "Clients_Database", "Clients_Database");

        Role lambdaRole = Role.Builder.create(this, "LambdaExecutionRole")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaRole")
                ))
                .build();

        Clients_Database.grantFullAccess(lambdaRole);

        Function SimpleHandler = Function.Builder.create(this, "SimpleHandler")
                .runtime(Runtime.JAVA_17)
                .handler("com.myorg.SimpleHandler::handleRequest")
                .code(Code.fromAsset("target"))
                .memorySize(512)
                .timeout(Duration.seconds(30))
                .role(lambdaRole)
                .environment(Map.of(
                        "DYNAMODB_TABLE_NAME", Clients_Database.getTableName()
                ))
                .build();
        CfnBot.SlotTypeProperty actionSlotType = CfnBot.SlotTypeProperty.builder()
                .name("ActionType")
                .description("Defines actions the user can take on an order")
                .slotTypeValues(List.of(
                        CfnBot.SlotTypeValueProperty.builder()
                                .sampleValue(CfnBot.SampleValueProperty.builder().value("update shipping address").build())
                                .build(),
                        CfnBot.SlotTypeValueProperty.builder()
                                .sampleValue(CfnBot.SampleValueProperty.builder().value("cancel order").build())
                                .build()
                ))
                .valueSelectionSetting(CfnBot.SlotValueSelectionSettingProperty.builder()
                        .resolutionStrategy("ORIGINAL_VALUE")
                        .build())
                .build();

        CfnBot.BotLocaleProperty botLocale = CfnBot.BotLocaleProperty.builder()
                .localeId("en_US")
                .nluConfidenceThreshold(0.8)
                .slotTypes(List.of(actionSlotType))
                .intents(List.of(
                        createIntent("change_order", "Handles order-related requests", actionSlotType),
                        createFallbackIntent()
                ))
                .build();

        CfnBot lexBot = CfnBot.Builder.create(this, "CustomerSupportBot")
                .name("CustomerSupportBot")
                .dataPrivacy(Map.of("ChildDirected", false))
                .idleSessionTtlInSeconds(300)
                .roleArn(lambdaRole.getRoleArn())
                .botLocales(List.of(botLocale))
                .build();

        CfnBotAlias botAlias = CfnBotAlias.Builder.create(this, "CustomerSupportBotAlias")
                .botId(lexBot.getAttrId())
                .botAliasName("BotAlias")
                .botVersion("1")
                .botAliasLocaleSettings(List.of(
                        CfnBotAlias.BotAliasLocaleSettingsItemProperty.builder()
                                .localeId("en_US")
                                .botAliasLocaleSetting(CfnBotAlias.BotAliasLocaleSettingsProperty.builder()
                                        .enabled(true)
                                        .codeHookSpecification(CfnBotAlias.CodeHookSpecificationProperty.builder()
                                                .lambdaCodeHook(CfnBotAlias.LambdaCodeHookProperty.builder()
                                                        .lambdaArn(SimpleHandler.getFunctionArn())
                                                        .codeHookInterfaceVersion("1.0")
                                                        .build())
                                                .build())
                                        .build())
                                .build()
                ))
                .build();
        botAlias.addDependency(lexBot);
        SimpleHandler.addPermission("LexInvokePermission",
                Permission.builder()
                        .principal(new ServicePrincipal("lex.amazonaws.com"))
                        .action("lambda:InvokeFunction")
                        .sourceArn(lexBot.getAttrArn())
                        .build()
        );
    }

    private CfnBot.IntentProperty createIntent(String name, String description, CfnBot.SlotTypeProperty actionSlotType) {
        CfnBot.PromptSpecificationProperty orderNumberPrompt = CfnBot.PromptSpecificationProperty.builder()
                .messageGroupsList(List.of(
                        CfnBot.MessageGroupProperty.builder()
                                .message(
                                        CfnBot.MessageProperty.builder()
                                                .plainTextMessage(
                                                        CfnBot.PlainTextMessageProperty.builder()
                                                                .value("Please provide your order number.")
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                ))
                .maxRetries(2)
                .allowInterrupt(true)
                .build();

        CfnBot.PromptSpecificationProperty shippingAddressPrompt = CfnBot.PromptSpecificationProperty.builder()
                .messageGroupsList(List.of(
                        CfnBot.MessageGroupProperty.builder()
                                .message(
                                        CfnBot.MessageProperty.builder()
                                                .plainTextMessage(
                                                        CfnBot.PlainTextMessageProperty.builder()
                                                                .value("Please provide the new shipping address.")
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                ))
                .maxRetries(2)
                .allowInterrupt(true)
                .build();
        CfnBot.PromptSpecificationProperty actionTypePrompt = CfnBot.PromptSpecificationProperty.builder()
                .messageGroupsList(List.of(
                        CfnBot.MessageGroupProperty.builder()
                                .message(
                                        CfnBot.MessageProperty.builder()
                                                .plainTextMessage(
                                                        CfnBot.PlainTextMessageProperty.builder()
                                                                .value("How would you like to modify your order ?")
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                ))
                .maxRetries(2)
                .allowInterrupt(true)
                .build();


        CfnBot.SlotProperty orderNumberSlot = CfnBot.SlotProperty.builder()
                .name("OrderNumber")
                .description("Order number")
                .slotTypeName("AMAZON.Number")
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Required")
                        .promptSpecification(orderNumberPrompt)
                        .build())
                .build();

        CfnBot.SlotProperty actionTypeSlot = CfnBot.SlotProperty.builder()
                .name("ActionType")
                .description("Action to perform")
                .slotTypeName(actionSlotType.getName())
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Required")
                        .promptSpecification(actionTypePrompt)
                        .build())
                .build();

        CfnBot.SlotProperty shippingAddressSlot = CfnBot.SlotProperty.builder()
                .name("ShippingAddress")
                .description("New shipping address")
                .slotTypeName("AMAZON.City")
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Required")
                        .promptSpecification(shippingAddressPrompt)
                        .build())
                .build();

        List<CfnBot.SlotPriorityProperty> slotPriorities = List.of(
                CfnBot.SlotPriorityProperty.builder()
                        .priority(1)
                        .slotName("OrderNumber")
                        .build(),
                CfnBot.SlotPriorityProperty.builder()
                        .priority(2)
                        .slotName("ActionType")
                        .build(),
                CfnBot.SlotPriorityProperty.builder()
                        .priority(3)
                        .slotName("ShippingAddress")
                        .build()
        );

        return CfnBot.IntentProperty.builder()
                .name(name)
                .description(description)
                .fulfillmentCodeHook(CfnBot.FulfillmentCodeHookSettingProperty.builder()
                        .enabled(true)
                        .build())
                .slots(List.of(orderNumberSlot, actionTypeSlot, shippingAddressSlot))
                .slotPriorities(slotPriorities)
                .sampleUtterances(List.of(
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want to {ActionType} ").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Please {ActionType} on order {OrderNumber} ").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Can you {ActionType} to {ShippingAddress} ?").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want to change my shipping address to {ShippingAddress} ?").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Can you change my shipping address to {ShippingAddress} ?").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want to change order {OrderNumber}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want to {ActionType}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I need to {ActionType}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("{ActionType}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want to change order").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("{ShippingAddress}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I need to update the {ShippingAddress}").build()
                ))
                .build();
    }

    private CfnBot.IntentProperty createFallbackIntent() {
        return CfnBot.IntentProperty.builder()
                .name("FallbackIntent")
                .description("Handles unknown user inputs")
                .parentIntentSignature("AMAZON.FallbackIntent")
                .build();
    }
}
