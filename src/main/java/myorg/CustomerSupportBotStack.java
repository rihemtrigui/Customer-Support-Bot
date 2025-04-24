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
        lambdaRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "s3:GetObject",  // Allow reading objects from the bucket
                        "s3:ListBucket"  // Allow listing objects in the bucket (optional, for listing grammar files)
                ))
                .resources(List.of(
                        "arn:aws:s3:::hpgrammar",       // Bucket-level permission (for ListBucket)
                        "arn:aws:s3:::hpgrammar/*"      // Object-level permission (for GetObject)
                ))
                .build());

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
        Role lexRole = Role.Builder.create(this, "LexBotRole")
                .assumedBy(new ServicePrincipal("lexv2.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonLexFullAccess")
                ))
                .build();

        lexRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "s3:GetObject",  // Allow reading GRXML files
                        "s3:ListBucket"  // Allow listing objects in the bucket (optional)
                ))
                .resources(List.of(
                        "arn:aws:s3:::hpgrammar",       // Bucket-level permission (for ListBucket)
                        "arn:aws:s3:::hpgrammar/*"      // Object-level permission (for GetObject)
                ))
                .build());

        CfnBot.SlotProperty orderNumberSlot = CfnBot.SlotProperty.builder()
                .name("OrderNumber")
                .description("Order number for reference")
                .slotTypeName("AMAZON.Number")
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Required")
                        .promptSpecification(createPromptSpecification("Please provide your order number."))
                        .build())
                .build();
        CfnBot.SlotProperty emailSlot = CfnBot.SlotProperty.builder()
                .name("Email")
                .description("Client's email")
                .slotTypeName("AMAZON.EmailAddress")
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Required")
                        .promptSpecification(createPromptSpecification("Please provide your email address."))
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
                                .build(),
                        CfnBot.SlotTypeValueProperty.builder()
                                .sampleValue(CfnBot.SampleValueProperty.builder().value("update_payment").build())
                                .synonyms(List.of(
                                        CfnBot.SampleValueProperty.builder().value("update payment method").build(),
                                        CfnBot.SampleValueProperty.builder().value("change payment method").build(),
                                        CfnBot.SampleValueProperty.builder().value("pay online").build()
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

        CfnBot.SlotTypeProperty paymentMethodSlotType = CfnBot.SlotTypeProperty.builder()
                .name("PaymentMethodType")
                .description("Defines the payment method for an order")
                .slotTypeValues(List.of(
                        CfnBot.SlotTypeValueProperty.builder()
                                .sampleValue(CfnBot.SampleValueProperty.builder().value("on_shipment").build())
                                .synonyms(List.of(
                                        CfnBot.SampleValueProperty.builder().value("pay on delivery").build(),
                                        CfnBot.SampleValueProperty.builder().value("cash on delivery").build(),
                                        CfnBot.SampleValueProperty.builder().value("cash").build()
                                        ))
                                .build(),
                        CfnBot.SlotTypeValueProperty.builder()
                                .sampleValue(CfnBot.SampleValueProperty.builder().value("online").build())
                                .synonyms(List.of(
                                        CfnBot.SampleValueProperty.builder().value("pay online").build(),
                                        CfnBot.SampleValueProperty.builder().value("credit card").build(),
                                        CfnBot.SampleValueProperty.builder().value("card").build()
                                        ))
                                .build()
                ))
                .valueSelectionSetting(CfnBot.SlotValueSelectionSettingProperty.builder()
                        .resolutionStrategy("TOP_RESOLUTION")
                        .build())
                .build();


        CfnBot.SlotProperty paymentMethodSlot = CfnBot.SlotProperty.builder()
                .name("PaymentMethod")
                .description("Desired payment method for the order")
                .slotTypeName(paymentMethodSlotType.getName())
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Optional")
                        .promptSpecification(createPromptSpecification("What payment method would you like to use? (on_shipment or online)"))
                        .build())
                .build();

        CfnBot.SlotProperty cardNumberSlot = CfnBot.SlotProperty.builder()
                .name("CardNumber")
                .description("User's credit card number")
                .slotTypeName("AMAZON.Number")
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Optional")
                        .promptSpecification(createPromptSpecification("Please provide your credit card number."))
                        .build())
                .build();

        CfnBot.SlotTypeProperty expirationDateSlotType = CfnBot.SlotTypeProperty.builder()
                .name("ExpirationDateType")
                .description("Expiration date in MM/YY format")
                .slotTypeValues(List.of(
                        CfnBot.SlotTypeValueProperty.builder()
                                .sampleValue(CfnBot.SampleValueProperty.builder().value("01/26").build())
                                .build()
                ))
                .valueSelectionSetting(CfnBot.SlotValueSelectionSettingProperty.builder()
                        .resolutionStrategy("TOP_RESOLUTION")
                        .build())
                .build();

        CfnBot.SlotProperty expirationDateSlot = CfnBot.SlotProperty.builder()
                .name("ExpirationDate")
                .description("Expiration date of the credit card")
                .slotTypeName(expirationDateSlotType.getName())
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Optional")
                        .promptSpecification(createPromptSpecification("Please provide the expiration date of your card in MM/YY format (e.g., 12/25)."))
                        .build())
                .build();

        CfnBot.SlotProperty cvvSlot = CfnBot.SlotProperty.builder()
                .name("CVV")
                .description("CVV code of the credit card")
                .slotTypeName("AMAZON.Number")
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Optional")
                        .promptSpecification(createPromptSpecification("Please provide the CVV code of your card (3 digits on the back of your card)."))
                        .build())
                .build();

        CfnBot.SlotProperty clientNameSlot = CfnBot.SlotProperty.builder()
                .name("Name")
                .description("Client's name")
                .slotTypeName("AMAZON.FreeFormInput")
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Required")
                        .promptSpecification(createPromptSpecification("Please provide your full name."))
                        .build())
                .build();

        CfnBot.SlotProperty productsSlot = CfnBot.SlotProperty.builder()
                .name("Products")
                .description("Product category")
                .slotTypeName("ProductsType")
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Required")
                        .promptSpecification(createPromptSpecification("What type of product would you like? For example, say 'laptop' or 'tablet'."))
                        .build())
                .build();

        CfnBot.SlotProperty productNameSlot = CfnBot.SlotProperty.builder()
                .name("ProductName")
                .description("Product model name")
                .slotTypeName("ProductNameType")
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Required")
                        .promptSpecification(createPromptSpecification("What is the product name? For example, say 'Streambook' or 'Slate'."))
                        .build())
                .build();

        CfnBot.SlotProperty productNumberSlot = CfnBot.SlotProperty.builder()
                .name("ProductNumber")
                .description("Product numeric identifier")
                .slotTypeName("ProductNumberType")
                .valueElicitationSetting(CfnBot.SlotValueElicitationSettingProperty.builder()
                        .slotConstraint("Required")
                        .promptSpecification(createPromptSpecification("What is the product number? For example, say '11' or '500'."))
                        .build())
                .build();

        CfnBot.SlotTypeProperty productsType = CfnBot.SlotTypeProperty.builder()
                .name("ProductsType")
                .description("Product categories")
                .externalSourceSetting(CfnBot.ExternalSourceSettingProperty.builder()
                        .grammarSlotTypeSetting(CfnBot.GrammarSlotTypeSettingProperty.builder()
                                .source(CfnBot.GrammarSlotTypeSourceProperty.builder()
                                        .s3BucketName("hpgrammar")
                                        .s3ObjectKey("products.grxml")
                                        .build())
                                .build())
                        .build())
                .build();

        CfnBot.SlotTypeProperty productNameType = CfnBot.SlotTypeProperty.builder()
                .name("ProductNameType")
                .description("Product model names")
                .externalSourceSetting(CfnBot.ExternalSourceSettingProperty.builder()
                        .grammarSlotTypeSetting(CfnBot.GrammarSlotTypeSettingProperty.builder()
                                .source(CfnBot.GrammarSlotTypeSourceProperty.builder()
                                        .s3BucketName("hpgrammar")
                                        .s3ObjectKey("product_names.grxml")
                                        .build())
                                .build())
                        .build())
                .build();

        CfnBot.SlotTypeProperty productNumberType = CfnBot.SlotTypeProperty.builder()
                .name("ProductNumberType")
                .description("Product numeric identifiers")
                .externalSourceSetting(CfnBot.ExternalSourceSettingProperty.builder()
                        .grammarSlotTypeSetting(CfnBot.GrammarSlotTypeSettingProperty.builder()
                                .source(CfnBot.GrammarSlotTypeSourceProperty.builder()
                                        .s3BucketName("hpgrammar")
                                        .s3ObjectKey("model_numbers.grxml")
                                        .build())
                                .build())
                        .build())
                .build();

        CfnBot.BotLocaleProperty botLocale = CfnBot.BotLocaleProperty.builder()
                .localeId("en_US")
                .nluConfidenceThreshold(0.7)
                .slotTypes(List.of(actionSlotType, paymentMethodSlotType, expirationDateSlotType,productNameType,productNumberType,productsType))
                .intents(List.of(
                        createChangeOrderIntent(orderNumberSlot, actionTypeSlot, shippingAddressSlot, paymentMethodSlot, cvvSlot, expirationDateSlot, cardNumberSlot),
                        createOrderHPItemIntent(productsSlot, productNameSlot, productNumberSlot, paymentMethodSlot, clientNameSlot, shippingAddressSlot,emailSlot, cardNumberSlot,
                                expirationDateSlot, cvvSlot),
                        createFallbackIntent(),
                        createGreetingsIntent()
                ))
                .voiceSettings(CfnBot.VoiceSettingsProperty.builder()
                        .voiceId("Joanna")
                        .build())
                .build();

        CfnBot lexBot = CfnBot.Builder.create(this, "CustomerSupportBot")
                .name("CustomerSupportBot")
                .dataPrivacy(Map.of("ChildDirected", false))
                .idleSessionTtlInSeconds(300)
                .roleArn(lexRole.getRoleArn())
                .botLocales(List.of(botLocale))
                .build();

    }
    private CfnBot.IntentProperty createGreetingsIntent() {
        return CfnBot.IntentProperty.builder()
                .name("GreetingsIntent")
                .description("Handles greetings")
                .sampleUtterances(List.of(
                        CfnBot.SampleUtteranceProperty.builder().utterance("Hello").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Hi").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Good morning").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Good evening").build()
                ))
                .dialogCodeHook(CfnBot.DialogCodeHookSettingProperty.builder()
                        .enabled(true)
                        .build())
                .fulfillmentCodeHook(CfnBot.FulfillmentCodeHookSettingProperty.builder()
                        .enabled(true)
                        .build())
                .build();
    }

    private CfnBot.IntentProperty createOrderHPItemIntent(CfnBot.SlotProperty productsSlot, CfnBot.SlotProperty productNameSlot , CfnBot.SlotProperty productNumberSlot ,
                                                          CfnBot.SlotProperty paymentMethodSlot, CfnBot.SlotProperty cardNumberSlot, CfnBot.SlotProperty expirationDateSlot,
                                                          CfnBot.SlotProperty cvvSlot,CfnBot.SlotProperty clientNameSlot,CfnBot.SlotProperty shippingAddressSlot,
                                                          CfnBot.SlotProperty emailSlot) {
        return CfnBot.IntentProperty.builder()
                .name("OrderHPItemIntent")
                .description("Handles ordering of HP items and payment processing")
                .slots(List.of(productsSlot, productNameSlot ,productNumberSlot,paymentMethodSlot,cardNumberSlot, expirationDateSlot, cvvSlot,
                        clientNameSlot,shippingAddressSlot,emailSlot))
                .slotPriorities(List.of(
                        CfnBot.SlotPriorityProperty.builder().priority(1).slotName("Products").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(2).slotName("ProductName").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(3).slotName("ProductNumber").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(4).slotName("Name").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(5).slotName("ShippingAddress").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(6).slotName("Email").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(7).slotName("PaymentMethod").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(8).slotName("CardNumber").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(9).slotName("ExpirationDate").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(10).slotName("CVV").build()
                ))
                .sampleUtterances(List.of(
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want to order an HP item").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want buy HP products").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Order a {ProductName} {Products} model {ProductNumber}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Purchase a {Products} {ProductName} {ProductNumber}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Buy a {ProductName} {Products} with model {ProductNumber}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Get me a {Products} {ProductName} with the number {ProductNumber}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want to buy a {Products} {ProductName} with the number {ProductNumber}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Get me a {Products}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want to have a {ProductName}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want to order a {ProductName}").build()

                        ))
                .dialogCodeHook(CfnBot.DialogCodeHookSettingProperty.builder()
                        .enabled(true)
                        .build())
                .fulfillmentCodeHook(CfnBot.FulfillmentCodeHookSettingProperty.builder()
                        .enabled(true)
                        .build())
                .build();
    }

    private CfnBot.IntentProperty createChangeOrderIntent(CfnBot.SlotProperty orderNumberSlot,CfnBot.SlotProperty actionTypeSlot,CfnBot.SlotProperty shippingAddressSlot,CfnBot.SlotProperty paymentMethodSlot,CfnBot.SlotProperty cvvSlot, CfnBot.SlotProperty expirationSlot, CfnBot.SlotProperty cardNumberSlot) {
        return CfnBot.IntentProperty.builder()
                .name("ChangeOrderIntent")
                .description("Acts as a router for order modifications")
                .slots(List.of(orderNumberSlot,actionTypeSlot,shippingAddressSlot,paymentMethodSlot,cvvSlot,expirationSlot,cardNumberSlot))
                .slotPriorities(List.of(
                        CfnBot.SlotPriorityProperty.builder().priority(1).slotName("OrderNumber").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(2).slotName("ActionType").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(3).slotName("ShippingAddress").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(4).slotName("PaymentMethod").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(5).slotName("CardNumber").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(6).slotName("ExpirationDate").build(),
                        CfnBot.SlotPriorityProperty.builder().priority(7).slotName("CVV").build()

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
                        CfnBot.SampleUtteranceProperty.builder().utterance("help me with {ActionType} my order").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Update payment method to {PaymentMethod} for order {OrderNumber}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("Change payment to {PaymentMethod}").build(),
                        CfnBot.SampleUtteranceProperty.builder().utterance("I want to pay {PaymentMethod} for order {OrderNumber}").build()

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
