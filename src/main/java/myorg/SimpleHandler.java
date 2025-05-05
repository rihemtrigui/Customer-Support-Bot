package myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;
import software.amazon.awssdk.services.lambda.LambdaClient;


import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Collectors;

public class SimpleHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private final LambdaClient lambdaClient = LambdaClient.create();  // Add this line
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final SesClient sesClient = SesClient.create();
    private static final String FAQ_HANDLER_ARN = "arn:aws:lambda:us-east-1:960673175457:function:faq_handler";
    private static final String TABLE_NAME = "Clients_Database";
    private static final String ALGOBOOK_API_KEY = "6617961216msh17b4859478138bcp17d8f3jsn9ca072fb9a3d";
    private static final String ALGOBOOK_API_HOST = "credit-card-validation-api-algobook.p.rapidapi.com";
    private static final String SENDER_EMAIL = "noreply.hpassistance@gmail.com";
    private static final String RECEIVER_EMAIL = "triguirihem13@gmail.com";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            if (event == null) {
                context.getLogger().log("Received null event");
                throw new IllegalArgumentException("Event cannot be null");
            }

            JsonNode rootNode = objectMapper.valueToTree(event);
            context.getLogger().log("Event JSON: " + rootNode.toString());

            String sessionId = rootNode.has("sessionId") ? rootNode.path("sessionId").asText() : "unknown";
            context.getLogger().log("Session ID: " + sessionId);

            JsonNode sessionState = rootNode.path("sessionState");
            if (sessionState.isMissingNode()) {
                context.getLogger().log("Missing sessionState in event");
                throw new IllegalStateException("Session state is missing in the event");
            }

            JsonNode intent = sessionState.path("intent");
            if (intent.isMissingNode()) {
                context.getLogger().log("Missing intent in sessionState");
                throw new IllegalStateException("Intent is missing in the sessionState");
            }

            String intentName = intent.has("name") ? intent.path("name").asText() : "UnknownIntent";
            context.getLogger().log("Intent Name: " + intentName);

            JsonNode sessionAttributes = sessionState.path("sessionAttributes");
            Map<String, Object> sessionAttributesMap = new HashMap<>();
            if (sessionAttributes != null && !sessionAttributes.isMissingNode()) {
                if (sessionAttributes.isObject()) {
                    try {
                        Map<String, Object> tempMap = objectMapper.convertValue(sessionAttributes, new TypeReference<Map<String, Object>>() {});
                        sessionAttributesMap = new HashMap<>(tempMap);
                    } catch (Exception e) {
                        context.getLogger().log("Failed to parse sessionAttributes: " + sessionAttributes.toString());
                        throw new RuntimeException("Error parsing sessionAttributes", e);
                    }
                } else {
                    context.getLogger().log("Session attributes is not an object, skipping: " + sessionAttributes.toString());
                }
            } else {
                context.getLogger().log("Session attributes are missing or not an object: " + (sessionAttributes != null ? sessionAttributes.toString() : "null"));
            }

            JsonNode slotsNode = intent.path("slots");
            Map<String, Object> slots = new HashMap<>();
            if (slotsNode != null && !slotsNode.isMissingNode()) {
                if (slotsNode.isObject()) {
                    try {
                        slots = objectMapper.convertValue(slotsNode, new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        context.getLogger().log("Failed to parse slots: " + slotsNode.toString());
                        throw new RuntimeException("Error parsing slots", e);
                    }
                } else {
                    context.getLogger().log("Slots is not an object, skipping: " + slotsNode.toString());
                }
            } else {
                context.getLogger().log("Slots are missing or not an object: " + (slotsNode != null ? slotsNode.toString() : "null"));
            }

            for (String slotName : slots.keySet()) {
                String slotValue = getOrRestoreSlot(slotName, slots, sessionAttributesMap, context);
                if (slotValue != null && !slotValue.isEmpty()) {
                    sessionAttributesMap.put(slotName, slotValue);
                }
            }
            context.getLogger().log("Session Attributes after slot processing: " + sessionAttributesMap);

            if ("OrderHPItemIntent".equals(intentName)) {
                return handleOrderHPItemIntent(intentName, slots, sessionAttributesMap, context);
            } else if ("ChangeOrderIntent".equals(intentName)) {
                return handleChangeOrderIntent(intentName, slots, sessionAttributesMap, context);
            } else if ("GreetingsIntent".equals(intentName)) {
                return handleGreetingsIntent(intentName, slots, sessionAttributesMap, context);
            } else if ("FAQIntent".equals(intentName)) {
                return delegateToFAQHandler(event,context,sessionAttributesMap);
            }
            else {
                return handleFallBackIntent(intentName, slots, sessionAttributesMap, context);
            }
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String stackTrace = sw.toString();
            context.getLogger().log("Error processing Lex event: " + e.getMessage() + "\nStack Trace: " + stackTrace);
            return buildLexResponse("UnknownIntent", "Error processing request. Please try again later.", "Failed", Map.of(), null);
        }
    }
    private Map<String, Object> delegateToFAQHandler(Map<String, Object> input, Context context, Map<String, Object> sessionAttributesMap) {
        context.getLogger().log("Delegating to FAQHandler for FAQIntent");
        try {
            String eventJson = objectMapper.writeValueAsString(input);
            context.getLogger().log("Event JSON sent to FAQHandler: " + eventJson);

            InvokeRequest invokeRequest = InvokeRequest.builder()
                    .functionName(FAQ_HANDLER_ARN)
                    .payload(SdkBytes.fromUtf8String(eventJson))
                    .build();

            InvokeResponse result = lambdaClient.invoke(invokeRequest);
            context.getLogger().log("FAQHandler invocation result: " + result.statusCode());
            String responseJson = result.payload().asUtf8String();
            context.getLogger().log("FAQHandler response: " + responseJson);

            Map<String, Object> responseMap = objectMapper.readValue(responseJson, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> dialogAction = (Map<String, Object>) responseMap.get("dialogAction");
            String fulfillmentState = (String) dialogAction.get("fulfillmentState");
            Map<String, Object> messageMap = (Map<String, Object>) dialogAction.get("message");
            String content = (String) messageMap.get("content");

            return Map.of(
                    "sessionState", Map.of(
                            "dialogAction", Map.of(
                                    "type", "Close",
                                    "fulfillmentState", fulfillmentState
                            ),
                            "sessionAttributes", sessionAttributesMap,
                            "intent", Map.of(
                                    "name", "FAQIntent",
                                    "state", fulfillmentState
                            )
                    ),
                    "messages", new Object[] {
                            Map.of(
                                    "contentType", "PlainText",
                                    "content", content
                            )
                    }
            );
        } catch (Exception e) {
            context.getLogger().log("Error invoking FAQHandler: " + e.getMessage());
            return buildLexResponse("FAQIntent", "Sorry, there was an error processing your FAQ request.", "Failed", sessionAttributesMap, null);
        }
    }
    private Map<String, Object> handleOrderHPItemIntent(String intentName, Map<String, Object> slots, Map<String, Object> sessionAttributesMap, Context context) {
        String products = getOrRestoreSlot("Products", slots, sessionAttributesMap, context);
        if (products == null || products.isEmpty()) {
            return buildLexResponse(intentName, "What type of product would you like to buy ?", "InProgress", sessionAttributesMap, "Products");
        }

        String productName = getOrRestoreSlot("ProductName", slots, sessionAttributesMap, context);
        if (productName == null || productName.isEmpty()) {
            return buildLexResponse(intentName, "What is the product name ?", "InProgress", sessionAttributesMap, "ProductName");
        }

        String productNumber = getOrRestoreSlot("ProductNumber", slots, sessionAttributesMap, context);
        if (productNumber == null || productNumber.isEmpty()) {
            return buildLexResponse(intentName, "What is the model number ?", "InProgress", sessionAttributesMap, "ProductNumber");
        }

        String clientName = getOrRestoreSlot("Name", slots, sessionAttributesMap, context);
        if (clientName == null || clientName.isEmpty()) {
            return buildLexResponse(intentName, "Please provide your full name", "InProgress", sessionAttributesMap, "Name");
        }

        String shippingAddress = getOrRestoreSlot("ShippingAddress", slots, sessionAttributesMap, context);
        if (shippingAddress == null || shippingAddress.isEmpty()) {
            return buildLexResponse(intentName, "Please provide your shipping address.", "InProgress", sessionAttributesMap, "ShippingAddress");
        }

        String email = getOrRestoreSlot("Email", slots, sessionAttributesMap, context);
        if (email == null || email.isEmpty()) {
            return buildLexResponse(intentName, "Please provide your email address.", "InProgress", sessionAttributesMap, "Email");
        }

        String paymentMethod = getOrRestoreSlot("PaymentMethod", slots, sessionAttributesMap, context);
        if (paymentMethod == null || paymentMethod.isEmpty()) {
            return buildLexResponse(intentName, "How would you like to pay ? You can choose between cash on delivery or online payment with a card.", "InProgress", sessionAttributesMap, "PaymentMethod");
        }

        String cardNumber = null;
        String expirationDate = null;
        String cvv = null;

        if (paymentMethod.equalsIgnoreCase("online")) {
            cardNumber = getOrRestoreSlot("CardNumber", slots, sessionAttributesMap, context);
            if (cardNumber == null || cardNumber.isEmpty()) {
                return buildLexResponse(intentName, "Please provide your credit card number to complete the payment.", "InProgress", sessionAttributesMap, "CardNumber");
            }

            expirationDate = getOrRestoreSlot("ExpirationDate", slots, sessionAttributesMap, context);
            if (expirationDate == null || expirationDate.isEmpty()) {
                return buildLexResponse(intentName, "Please provide the expiration date of your card in MM/YY format (e.g., 12/25).", "InProgress", sessionAttributesMap, "ExpirationDate");
            }

            cvv = getOrRestoreSlot("CVV", slots, sessionAttributesMap, context);
            if (cvv == null || cvv.isEmpty()) {
                return buildLexResponse(intentName, "Please provide the CVV code of your card (3 digits for most cards, 4 digits for American Express).", "InProgress", sessionAttributesMap, "CVV");
            }

            context.getLogger().log("Calling validateCreditCard with card: " + cardNumber + " and CVV: " + cvv);
            boolean isCardValid = validateCreditCard(cardNumber, cvv, context);
            if (!isCardValid) {
                boolean isAmex = cardNumber.startsWith("34") || cardNumber.startsWith("37");
                String cvvRequirement = isAmex ? "4-digit CVV" : "3-digit CVV";
                context.getLogger().log("Card validation failed, prompting for " + cvvRequirement);
                return buildLexResponse(intentName,
                        "Invalid credit card details. Please ensure you're using a valid card number and " + cvvRequirement + ".",
                        "InProgress", sessionAttributesMap, "CVV");
            }
        }

        // All order slots are filled, check if SuggestionResponse is filled
        String suggestionResponse = getOrRestoreSlot("SuggestionResponse", slots, sessionAttributesMap, context);

        if (suggestionResponse == null) {
            // Place the order and elicit SuggestionResponse
            int orderNumber = generateOrderNumber();

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("order_number", AttributeValue.builder().n(String.valueOf(orderNumber)).build());
            item.put("clients_name", AttributeValue.builder().s(clientName).build());
            item.put("product_type", AttributeValue.builder().s(products).build());
            item.put("product_name", AttributeValue.builder().s(productName).build());
            item.put("product_number", AttributeValue.builder().s(productNumber != null ? productNumber : "N/A").build());
            item.put("payment_method", AttributeValue.builder().s(paymentMethod).build());
            item.put("shipping_address", AttributeValue.builder().s(shippingAddress).build());
            item.put("email_address", AttributeValue.builder().s(email).build());

            PutItemRequest putRequest = PutItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .item(item)
                    .build();
            dynamoDbClient.putItem(putRequest);

            String subject = "Order Confirmation - Order #" + orderNumber;
            String body = String.format(
                    "Dear %s,\n\nYour order has been successfully placed!\n\nOrder Details:\n- Order Number: #%d\n- Product: %s %s %s\n- Payment Method: %s\n- Shipping Address: %s\n\nThank you for shopping with us!",
                    clientName, orderNumber, products, productName, productNumber != null ? productNumber : "N/A", paymentMethod, shippingAddress
            );

            sendEmail(email, subject, body, context);

            String confirmationMessage = String.format(
                    "Your order has been successfully placed! Your order number is #%d.",
                    orderNumber
            );

            // Compute SuggestedURL and store for later use
            String suggestedURL = getSuggestedURL(products);
            sessionAttributesMap.put("SuggestedURL", suggestedURL);
            sessionAttributesMap.put("Products", products);
            sessionAttributesMap.put("orderNumber", String.valueOf(orderNumber));
            context.getLogger().log("Set SuggestedURL in session attributes: " + suggestedURL);

            // Elicit SuggestionResponse with a plain-text message followed by an ImageResponseCard
            JsonNode card = getRelatedArticleCard(products, String.valueOf(orderNumber));
            return buildLexResponseWithTextAndCard(
                    intentName,
                    confirmationMessage,
                    "InProgress",
                    sessionAttributesMap,
                    "SuggestionResponse",
                    card
            );
        }

        // SuggestionResponse is filled, handle the response and close the dialog
        String cleanResponse = suggestionResponse.replace("\"", "").trim().toLowerCase();
        context.getLogger().log("Cleaned SuggestionResponse: " + cleanResponse);

        String suggestedURL = (String) sessionAttributesMap.get("SuggestedURL");
        if (suggestedURL == null) {
            context.getLogger().log("Suggestion url is not found in session attributes, recomputing");
            String productsFromSession = (String) sessionAttributesMap.get("Products");
            if (productsFromSession != null) {
                suggestedURL = getSuggestedURL(productsFromSession);
                context.getLogger().log("Recomputed SuggestedURL using Products: " + suggestedURL);
            } else {
                context.getLogger().log("Products not found in session attributes, using fallback URL");
                suggestedURL = "https://bit.ly/hp-accessories";
            }
        } else {
            context.getLogger().log("Found SuggestedURL in session attributes: " + suggestedURL);
        }

        // Retrieve orderNumber from session attributes
        String orderNumberStr = (String) sessionAttributesMap.get("orderNumber");
        int orderNumber = orderNumberStr != null ? Integer.parseInt(orderNumberStr) : 0;

        // Clean up session attributes
        sessionAttributesMap.remove("SuggestedURL");
        sessionAttributesMap.remove("Products");
        sessionAttributesMap.remove("orderNumber");
        sessionAttributesMap.remove("ProductName");
        sessionAttributesMap.remove("ProductNumber");
        sessionAttributesMap.remove("Name");
        sessionAttributesMap.remove("ShippingAddress");
        sessionAttributesMap.remove("Email");
        sessionAttributesMap.remove("PaymentMethod");
        sessionAttributesMap.remove("CardNumber");
        sessionAttributesMap.remove("ExpirationDate");
        sessionAttributesMap.remove("CVV");
        sessionAttributesMap.remove("SuggestionResponse");

        if ("yes".equals(cleanResponse)) {
            JsonNode urlCard = getUrlCard(suggestedURL, products);
            return buildLexResponseWithCardOnly(
                    intentName,
                    "Fulfilled",
                    sessionAttributesMap,
                    null,
                    urlCard
            );
        } else if ("no".equals(cleanResponse)) {
            String finalMessage = "Okay, no problem. Thank you for your order!";
            return buildLexResponse(intentName, finalMessage, "Fulfilled", sessionAttributesMap, null);
        } else {
            String finalMessage = "I didn't understand your response. Thank you for your order!";
            return buildLexResponse(intentName, finalMessage, "Fulfilled", sessionAttributesMap, null);
        }
    }

    private Map<String, Object> handleChangeOrderIntent(String intentName, Map<String, Object> slots, Map<String, Object> sessionAttributesMap, Context context) {
        String orderNumberText = getOrRestoreSlot("OrderNumber", slots, sessionAttributesMap, context);
        if (orderNumberText == null || orderNumberText.isEmpty()) {
            return buildLexResponse(intentName, "Please provide your order number.", "InProgress", sessionAttributesMap, "OrderNumber");
        }

        int orderNumber;
        try {
            orderNumber = Integer.parseInt(orderNumberText);
            if (orderNumber <= 0) {
                return buildLexResponse(intentName, "Order number must be a positive integer. Please provide a valid order number.", "InProgress", sessionAttributesMap, "OrderNumber");
            }
        } catch (NumberFormatException e) {
            return buildLexResponse(intentName, "Invalid order number format. Please provide a valid order number.", "InProgress", sessionAttributesMap, "OrderNumber");
        }

        Map<String, AttributeValue> orderItem = getOrderItem(orderNumber);
        if (orderItem == null) {
            return buildLexResponse(intentName, "Order number " + orderNumber + " does not exist. Please check and try again.", "Failed", sessionAttributesMap, null);
        }

        String action = getOrRestoreSlot("ActionType", slots, sessionAttributesMap, context);
        if (action == null || action.isEmpty()) {
            return buildLexResponse(intentName, "How can I help you with your order ?", "InProgress", sessionAttributesMap, "ActionType");
        }

        if (action.equalsIgnoreCase("update shipping address")) {
            String shippingAddress = getOrRestoreSlot("ShippingAddress", slots, sessionAttributesMap, context);
            if (shippingAddress == null || shippingAddress.isEmpty()) {
                return buildLexResponse(intentName, "Please provide the new shipping address.", "InProgress", sessionAttributesMap, "ShippingAddress");
            }
            updateShippingAddress(orderNumber, shippingAddress);
            String confirmationMessage = "The shipping address for order " + orderNumber + " has been successfully updated to " + shippingAddress + ".";
            return buildLexResponse(intentName, confirmationMessage, "Fulfilled", sessionAttributesMap, null);
        }

        if (action.equalsIgnoreCase("cancel")) {
            deleteOrder(orderNumber);
            String confirmationMessage = "Order number " + orderNumber + " has been successfully deleted.";
            return buildLexResponse(intentName, confirmationMessage, "Fulfilled", sessionAttributesMap, null);
        }

        if (action.equalsIgnoreCase("update_payment")) {
            String paymentMethod = orderItem.get("payment_method").s();
            context.getLogger().log("Processing update_payment for payment method: " + paymentMethod);
            if (paymentMethod.equalsIgnoreCase("online")) {
                return buildLexResponse(intentName, "The order with number " + orderNumber + " has already been paid online.", "Fulfilled", sessionAttributesMap, null);
            } else if (paymentMethod.equalsIgnoreCase("cash")) {
                String cardNumber = getOrRestoreSlot("CardNumber", slots, sessionAttributesMap, context);
                if (cardNumber == null || cardNumber.isEmpty()) {
                    return buildLexResponse(intentName, "Please provide your credit card number to fulfill the payment.", "InProgress", sessionAttributesMap, "CardNumber");
                }

                String expirationDate = getOrRestoreSlot("ExpirationDate", slots, sessionAttributesMap, context);
                if (expirationDate == null || expirationDate.isEmpty()) {
                    return buildLexResponse(intentName, "Please provide the expiration date of your card in MM/YY format (e.g., 12/25).", "InProgress", sessionAttributesMap, "ExpirationDate");
                }

                String cvv = getOrRestoreSlot("CVV", slots, sessionAttributesMap, context);
                if (cvv == null || cvv.isEmpty()) {
                    return buildLexResponse(intentName, "Please provide the CVV code of your card (3 digits for most cards, 4 digits for American Express).", "InProgress", sessionAttributesMap, "CVV");
                }

                context.getLogger().log("Calling validateCreditCard with card: " + cardNumber + " and CVV: " + cvv);
                boolean isCardValid = validateCreditCard(cardNumber, cvv, context);
                if (!isCardValid) {
                    boolean isAmex = cardNumber.startsWith("34") || cardNumber.startsWith("37");
                    String cvvRequirement = isAmex ? "4-digit CVV" : "3-digit CVV";
                    context.getLogger().log("Card validation failed, prompting for " + cvvRequirement);
                    return buildLexResponse(intentName,
                            "Invalid credit card details. Please ensure you're using a valid card number and " + cvvRequirement + ".",
                            "InProgress", sessionAttributesMap, "CVV");
                }

                updatePaymentMethod(orderNumber, "online");
                return buildLexResponse(intentName, "Your order has been paid successfully.", "Fulfilled", sessionAttributesMap, null);
            } else {
                return buildLexResponse(intentName, "Unknown payment method for order " + orderNumber + ". Please contact support.", "Failed", sessionAttributesMap, null);
            }
        }
        return buildLexResponse(intentName, "How can I help you with your order ?", "InProgress", sessionAttributesMap, "ActionType");
    }

    private Map<String, Object> handleGreetingsIntent(String intentName, Map<String, Object> slots, Map<String, Object> sessionAttributesMap, Context context) {
        String greetingMessage = "Hello! Welcome to HP SmartBot! How may I help you today?";
        return buildLexResponse(intentName, greetingMessage, "Fulfilled", sessionAttributesMap, null);
    }

    private Map<String, Object> handleFallBackIntent(String intentName, Map<String, Object> slots, Map<String, Object> sessionAttributesMap, Context context) {
        String errorMessage = "I'm sorry, I couldn't understand your request. Could you please clarify your message?";
        return buildLexResponse(intentName, errorMessage, "Fulfilled", sessionAttributesMap, null);
    }

    private String getOrRestoreSlot(String slotName, Map<String, Object> slots, Map<String, Object> sessionAttributesMap, Context context) {
        String slotValue = null;
        if (slots.get(slotName) != null) {
            JsonNode slotNode = new ObjectMapper().valueToTree(slots.get(slotName));
            if (slotNode.has("value") && slotNode.path("value").has("interpretedValue")) {
                slotValue = slotNode.path("value").path("interpretedValue").asText();
                if (slotValue.startsWith("\"") && slotValue.endsWith("\"")) {
                    slotValue = slotValue.substring(1, slotValue.length() - 1);
                }
                sessionAttributesMap.put(slotName, slotValue);
                context.getLogger().log(slotName + " stored in session attributes: " + slotValue);
            }
        } else if (sessionAttributesMap.containsKey(slotName)) {
            slotValue = (String) sessionAttributesMap.get(slotName);
            context.getLogger().log("Restored " + slotName + " from session attributes: " + slotValue);
        }
        return slotValue;
    }

    private Map<String, Object> buildLexResponse(String intentName, String message, String intentState, Map<String, Object> sessionAttributes, String slotToElicit) {
        Map<String, Object> dialogAction = new HashMap<>();

        if (slotToElicit != null && (slotToElicit.equals("OrderNumber") || slotToElicit.equals("ActionType") || slotToElicit.equals("ShippingAddress") ||
                slotToElicit.equals("PaymentMethod") || slotToElicit.equals("CardNumber") || slotToElicit.equals("ExpirationDate") || slotToElicit.equals("CVV") ||
                slotToElicit.equals("Products") || slotToElicit.equals("ProductName") || slotToElicit.equals("ProductNumber") || slotToElicit.equals("Name") ||
                slotToElicit.equals("Email") || slotToElicit.equals("SuggestionResponse"))) {
            dialogAction.put("type", "ElicitSlot");
            dialogAction.put("slotToElicit", slotToElicit);
        } else {
            dialogAction.put("type", "Close");
        }

        // Define valid slot names based on the intent
        Set<String> validSlots = switch (intentName) {
            case "OrderHPItemIntent" ->
                    Set.of("Products", "ProductName", "ProductNumber", "Name", "ShippingAddress", "Email", "PaymentMethod", "CardNumber", "ExpirationDate", "CVV", "SuggestionResponse");
            case "ChangeOrderIntent" ->
                    Set.of("OrderNumber", "ActionType", "ShippingAddress", "CardNumber", "ExpirationDate", "CVV");
            default -> Set.of();
        };

        // Only include session attributes that correspond to valid slot names for the current intent
        Map<String, Object> slots = new HashMap<>();
        for (String key : sessionAttributes.keySet()) {
            if (validSlots.contains(key)) {
                slots.put(key, Map.of("value", Map.of("interpretedValue", sessionAttributes.get(key))));
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("sessionState", Map.of(
                "dialogAction", dialogAction,
                "intent", Map.of(
                        "name", intentName,
                        "state", intentState,
                        "slots", slots
                ),
                "sessionAttributes", sessionAttributes != null ? sessionAttributes : new HashMap<>()
        ));

        response.put("messages", new Object[]{
                Map.of(
                        "contentType", "PlainText",
                        "content", message
                )
        });

        return response;
    }

    private Map<String, Object> buildLexResponseWithTextAndCard(String intentName, String textMessage, String intentState, Map<String, Object> sessionAttributes, String slotToElicit, JsonNode card) {
        Map<String, Object> dialogAction = new HashMap<>();

        if (slotToElicit != null && (slotToElicit.equals("OrderNumber") || slotToElicit.equals("ActionType") || slotToElicit.equals("ShippingAddress") ||
                slotToElicit.equals("PaymentMethod") || slotToElicit.equals("CardNumber") || slotToElicit.equals("ExpirationDate") || slotToElicit.equals("CVV") ||
                slotToElicit.equals("Products") || slotToElicit.equals("ProductName") || slotToElicit.equals("ProductNumber") || slotToElicit.equals("Name") ||
                slotToElicit.equals("Email") || slotToElicit.equals("SuggestionResponse"))) {
            dialogAction.put("type", "ElicitSlot");
            dialogAction.put("slotToElicit", slotToElicit);
        } else {
            dialogAction.put("type", "Close");
        }

        // Define valid slot names based on the intent
        Set<String> validSlots = switch (intentName) {
            case "OrderHPItemIntent" ->
                    Set.of("Products", "ProductName", "ProductNumber", "Name", "ShippingAddress", "Email", "PaymentMethod", "CardNumber", "ExpirationDate", "CVV", "SuggestionResponse");
            case "ChangeOrderIntent" ->
                    Set.of("OrderNumber", "ActionType", "ShippingAddress", "CardNumber", "ExpirationDate", "CVV");
            default -> Set.of();
        };

        // Only include session attributes that correspond to valid slot names for the current intent
        Map<String, Object> slots = new HashMap<>();
        for (String key : sessionAttributes.keySet()) {
            if (validSlots.contains(key)) {
                slots.put(key, Map.of("value", Map.of("interpretedValue", sessionAttributes.get(key))));
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("sessionState", Map.of(
                "dialogAction", dialogAction,
                "intent", Map.of(
                        "name", intentName,
                        "state", intentState,
                        "slots", slots
                ),
                "sessionAttributes", sessionAttributes != null ? sessionAttributes : new HashMap<>()
        ));

        List<Map<String, Object>> messageList = new ArrayList<>();

        // Add plain-text message
        messageList.add(Map.of(
                "contentType", "PlainText",
                "content", textMessage
        ));

        // Add ImageResponseCard
        JsonNode content = card.get("content");
        ArrayNode buttons = (ArrayNode) content.get("buttons");
        List<Map<String, String>> buttonList = new ArrayList<>();

        for (JsonNode buttonNode : buttons) {
            buttonList.add(Map.of(
                    "text", buttonNode.get("text").asText(),
                    "value", buttonNode.get("value").asText()
            ));
        }

        Map<String, Object> cardMap = new HashMap<>();
        if (content.has("title")) {
            cardMap.put("title", content.get("title").asText());
        }
        cardMap.put("subtitle", content.get("subtitle").asText());
        cardMap.put("buttons", buttonList);

        messageList.add(Map.of(
                "contentType", "ImageResponseCard",
                "imageResponseCard", cardMap
        ));

        response.put("messages", messageList.toArray());
        return response;
    }

    private Map<String, Object> buildLexResponseWithCardOnly(
            String intentName,
            String intentState,
            Map<String, Object> sessionAttributes,
            String slotToElicit,
            JsonNode card) {

        Map<String, Object> dialogAction = new HashMap<>();
        dialogAction.put("type", slotToElicit != null ? "ElicitSlot" : "Close");
        if (slotToElicit != null) {
            dialogAction.put("slotToElicit", slotToElicit);
        }

        // Prepare the card message
        JsonNode content = card.get("content");
        ArrayNode buttons = (ArrayNode) content.get("buttons");

        List<Map<String, String>> buttonList = new ArrayList<>();
        for (JsonNode buttonNode : buttons) {
            String buttonValue = buttonNode.get("value").asText();
            if (buttonValue.length() > 50) {
                throw new IllegalArgumentException("Button value exceeds 50 characters: " + buttonValue);
            }
            buttonList.add(Map.of(
                    "text", buttonNode.get("text").asText(),
                    "value", buttonValue
            ));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("sessionState", Map.of(
                "dialogAction", dialogAction,
                "intent", Map.of(
                        "name", intentName,
                        "state", intentState,
                        "slots", sessionAttributes.entrySet().stream()
                                .filter(e -> isValidSlotForIntent(intentName, e.getKey()))
                                .collect(Collectors.toMap(
                                        Map.Entry::getKey,
                                        e -> Map.of("value", Map.of("interpretedValue", e.getValue()))
                                ))
                ),
                "sessionAttributes", sessionAttributes
        ));

        // Only include the card in messages
        response.put("messages", new Object[] {
                Map.of(
                        "contentType", "ImageResponseCard",
                        "imageResponseCard", Map.of(
                                "title", content.get("title").asText(),
                                "subtitle", content.get("subtitle").asText(),
                                "buttons", buttonList
                        )
                )
        });

        return response;
    }

    private boolean isValidSlotForIntent(String intentName, String slotName) {
        return switch (intentName) {
            case "OrderHPItemIntent" ->
                    Set.of("Products", "ProductName", "ProductNumber", "Name",
                            "ShippingAddress", "Email", "PaymentMethod", "CardNumber",
                            "ExpirationDate", "CVV", "SuggestionResponse").contains(slotName);
            case "ChangeOrderIntent" ->
                    Set.of("OrderNumber", "ActionType", "ShippingAddress",
                            "CardNumber", "ExpirationDate", "CVV").contains(slotName);
            default -> false;
        };
    }

    private JsonNode getUrlCard(String url, String productType) {
        String itemName = getItemNameFromProductType(productType);

        ObjectNode card = objectMapper.createObjectNode();
        card.put("type", "Generic");
        card.put("version", 1);

        ObjectNode content = objectMapper.createObjectNode();
        content.put("title", "Check Out This Item!");
        content.put("subtitle", "Click below to view the " + itemName + ".");

        ArrayNode buttons = objectMapper.createArrayNode();
        ObjectNode button = objectMapper.createObjectNode();
        button.put("text", "View Item");
        button.put("value", url); // The shortened URL is set directly as the button's value
        buttons.add(button);

        content.set("buttons", buttons);
        card.set("content", content);
        return card;
    }

    private String getItemNameFromProductType(String productType) {
        return switch (productType.toLowerCase()) {
            case "laptop" -> "docking station";
            case "tablet" -> "stylus";
            case "printer" -> "ink cartridge";
            case "touchpad" -> "wireless mouse";
            case "ipad" -> "tablet case";
            case "computer" -> "monitor";
            case "p_c" -> "keyboard";
            default -> "accessory";
        };
    }

    private Map<String, AttributeValue> getOrderItem(int orderNumber) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("order_number", AttributeValue.builder().n(String.valueOf(orderNumber)).build()))
                .build();
        GetItemResponse response = dynamoDbClient.getItem(request);
        if (response.hasItem()) {
            return response.item();
        }
        return null;
    }

    private void deleteOrder(int orderNumber) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("order_number", AttributeValue.builder().n(String.valueOf(orderNumber)).build());

        DeleteItemRequest deleteRequest = DeleteItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .build();

        dynamoDbClient.deleteItem(deleteRequest);
    }

    private void updateShippingAddress(int orderNumber, String newAddress) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("order_number", AttributeValue.builder().n(String.valueOf(orderNumber)).build());

        Map<String, AttributeValue> attributeValues = new HashMap<>();
        attributeValues.put(":newAddress", AttributeValue.builder().s(newAddress).build());

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .updateExpression("SET shipping_address = :newAddress")
                .expressionAttributeValues(attributeValues)
                .build();

        dynamoDbClient.updateItem(request);
    }

    private void updatePaymentMethod(int orderNumber, String newPaymentMethod) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("order_number", AttributeValue.builder().n(String.valueOf(orderNumber)).build());

        Map<String, AttributeValue> attributeValues = new HashMap<>();
        attributeValues.put(":newPaymentMethod", AttributeValue.builder().s(newPaymentMethod).build());

        Map<String, String> expressionAttributeNames = new HashMap<>();
        expressionAttributeNames.put("#pm", "payment_method");

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .updateExpression("SET #pm = :newPaymentMethod")
                .expressionAttributeValues(attributeValues)
                .expressionAttributeNames(expressionAttributeNames)
                .build();

        dynamoDbClient.updateItem(request);
    }

    private boolean validateCreditCard(String cardNumber, String cvv, Context context) {
        context.getLogger().log("Entering validateCreditCard with card: " + cardNumber + " and CVV: " + cvv);
        try {
            if (cardNumber == null || cardNumber.length() < 13 || cardNumber.length() > 19) {
                context.getLogger().log("Invalid card number length: " + (cardNumber != null ? cardNumber.length() : "null"));
                return false;
            }
            if (cvv == null || cvv.isEmpty()) {
                context.getLogger().log("CVV is null or empty");
                return false;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://credit-card-validation-api-algobook.p.rapidapi.com/v1/card/verify?number=" + cardNumber))
                    .header("X-RapidAPI-Host", ALGOBOOK_API_HOST)
                    .header("X-RapidAPI-Key", ALGOBOOK_API_KEY)
                    .method("GET", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            context.getLogger().log("API Status: " + response.statusCode());
            context.getLogger().log("Algobook API Response: " + response.body());

            ObjectMapper mapper = new ObjectMapper();
            JsonNode responseNode = mapper.readTree(response.body());

            JsonNode isValidNode = responseNode.path("valid");
            if (!isValidNode.asBoolean(false)) {
                context.getLogger().log("Card number validation failed via API");
                return false;
            }

            JsonNode cardTypeNode = responseNode.path("cardType");
            String cardType = cardTypeNode.asText("UNKNOWN");
            String cleanCvv = cvv.replaceAll("[^0-9]", "");

            context.getLogger().log("Card type identified as: " + cardType);
            context.getLogger().log("CVV provided: " + cleanCvv + " (length: " + cleanCvv.length() + ")");

            boolean isAmex = cardType.equalsIgnoreCase("American Express") ||
                    cardNumber.startsWith("34") ||
                    cardNumber.startsWith("37");

            if (isAmex) {
                if (cleanCvv.length() != 4) {
                    context.getLogger().log("Validation failed: American Express requires 4-digit CVV, got " + cleanCvv.length());
                    return false;
                }
            } else {
                if (cleanCvv.length() != 3) {
                    context.getLogger().log("Validation failed: " + cardType + " requires 3-digit CVV, got " + cleanCvv.length());
                    return false;
                }
            }

            if (!cleanCvv.matches("\\d+")) {
                context.getLogger().log("Validation failed: CVV contains invalid characters: " + cleanCvv);
                return false;
            }

            context.getLogger().log("Credit card and CVV validation successful");
            return true;
        } catch (Exception e) {
            context.getLogger().log("Error validating credit card: " + e.getMessage());
            return false;
        }
    }

    private boolean sendEmail(String toEmail, String subject, String body, Context context) {
        int retries = 3;
        for (int i = 0; i < retries; i++) {
            try {
                String recipientEmail = RECEIVER_EMAIL;
                Destination destination = Destination.builder()
                        .toAddresses(recipientEmail)
                        .build();
                String greeting = "";
                String mainMessage = "";
                Map<String, String> orderDetails = new LinkedHashMap<>();
                String[] lines = body.split("\n");
                boolean inOrderDetails = false;
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("Dear")) {
                        greeting = line;
                    } else if (line.equals("Order Details:")) {
                        inOrderDetails = true;
                    } else if (inOrderDetails && line.startsWith("-")) {
                        String[] parts = line.substring(2).split(":", 2);
                        if (parts.length == 2) {
                            orderDetails.put(parts[0].trim(), parts[1].trim());
                        }
                    } else if (!line.equals("Order Details:") && !inOrderDetails) {
                        mainMessage += (mainMessage.isEmpty() ? "" : "<br>") + line;
                    }
                }

                StringBuilder orderDetailsHtml = new StringBuilder();
                if (!orderDetails.isEmpty()) {
                    orderDetailsHtml.append("<h2 style=\"font-size: 18px; color: #0096D6; margin: 20px 0 10px;\">Order Details</h2>")
                            .append("<table border=\"0\" cellpadding=\"8\" cellspacing=\"0\" width=\"100%\" style=\"background-color: #F9F9F9; border: 1px solid #E0E0E0;\">");
                    for (Map.Entry<String, String> entry : orderDetails.entrySet()) {
                        orderDetailsHtml.append("<tr>")
                                .append("<td style=\"font-size: 14px; font-weight: bold; color: #333333;\">").append(entry.getKey()).append(":</td>")
                                .append("<td style=\"font-size: 14px; color: #333333;\">").append(entry.getValue()).append("</td>")
                                .append("</tr>");
                    }
                    orderDetailsHtml.append("</table>");
                }

                String htmlBody = "<!DOCTYPE html>" +
                        "<html>" +
                        "<head>" +
                        "<meta charset=\"UTF-8\">" +
                        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                        "<title>" + subject + "</title>" +
                        "</head>" +
                        "<body style=\"margin: 0; padding: 0; font-family: Arial, sans-serif; background-color: #F5F5F5; color: #333333;\">" +
                        "<table align=\"center\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"max-width: 600px; background-color: #FFFFFF; border: 1px solid #E0E0E0;\">" +
                        "<tr>" +
                        "<td style=\"padding: 20px; text-align: center; background-color: #0096D6;\">" +
                        "<img src=\"https://logo-marque.com/wp-content/uploads/2020/12/Hewlett-Packard-Logo-2008-2014.png\" alt=\"HP Logo\" style=\"max-width: 150px;\">" +
                        "<h1 style=\"color: #FFFFFF; font-size: 24px; margin: 10px 0;\">" + subject + "</h1>" +
                        "</td>" +
                        "</tr>" +
                        "<tr>" +
                        "<td style=\"padding: 20px;\">" +
                        "<p style=\"font-size: 16px; line-height: 1.5;\">" + greeting + "</p>" +
                        "<p style=\"font-size: 16px; line-height: 1.5;\">" + mainMessage + "</p>" +
                        orderDetailsHtml.toString() +
                        "</td>" +
                        "</tr>" +
                        "<tr>" +
                        "<td style=\"padding: 20px; text-align: center; background-color: #F5F5F5; border-top: 1px solid #E0E0E0;\">" +
                        "<p style=\"font-size: 14px; line-height: 1.5; color: #666666; margin: 0;\">Thank you for shopping with us!</p>" +
                        "<p style=\"font-size: 14px; line-height: 1.5; color: #666666; margin: 5px 0;\">HP SmartBot Team</p>" +
                        "<p style=\"font-size: 14px; line-height: 1.5; color: #666666; margin: 0;\">Contact us at: <a href=\"mailto:support@hp.com\" style=\"color: #0096D6; text-decoration: none;\">support@hp.com</a></p>" +
                        "</td>" +
                        "</tr>" +
                        "</table>" +
                        "</body>" +
                        "</html>";

                Message message = Message.builder()
                        .subject(Content.builder().data(subject).build())
                        .body(Body.builder()
                                .text(Content.builder().data(body).build())
                                .html(Content.builder().data(htmlBody).build())
                                .build())
                        .build();

                SendEmailRequest emailRequest = SendEmailRequest.builder()
                        .source(SENDER_EMAIL)
                        .destination(destination)
                        .message(message)
                        .build();

                sesClient.sendEmail(emailRequest);
                context.getLogger().log("Email sent successfully to " + recipientEmail);
                return true;
            } catch (Exception e) {
                context.getLogger().log("Attempt " + (i + 1) + " failed: Error sending email to " + toEmail + ": " + e.getMessage());
                context.getLogger().log("Stack trace: " + Arrays.toString(e.getStackTrace()));
                if (i == retries - 1) {
                    context.getLogger().log("All retries failed for sending email to " + toEmail);
                    return false;
                }
                try {
                    Thread.sleep(1000 * (i + 1));
                } catch (InterruptedException ie) {
                    context.getLogger().log("Retry interrupted: " + ie.getMessage());
                }
            }
        }
        return false;
    }

    private int generateOrderNumber() {
        ScanRequest scanRequest = ScanRequest.builder()
                .tableName(TABLE_NAME)
                .build();
        ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);

        int highestOrderNumber = 0;
        for (Map<String, AttributeValue> item : scanResponse.items()) {
            AttributeValue orderNumberAttr = item.get("order_number");
            if (orderNumberAttr != null && orderNumberAttr.n() != null) {
                int orderNumber = Integer.parseInt(orderNumberAttr.n());
                highestOrderNumber = Math.max(highestOrderNumber, orderNumber);
            }
        }

        return highestOrderNumber == 0 ? 1000 : highestOrderNumber + 1;
    }

    private JsonNode getRelatedArticleCard(String productType, String orderNumber) {
        String description = getSuggestionDescription(productType);

        ObjectNode card = objectMapper.createObjectNode();
        card.put("type", "Generic");
        card.put("version", 1);

        ObjectNode content = objectMapper.createObjectNode();
        content.put("title", "You may like this item as well!");
        content.put("subtitle", description);

        ArrayNode buttons = objectMapper.createArrayNode();
        ObjectNode button1 = objectMapper.createObjectNode();
        button1.put("text", "Yes, show me");
        button1.put("value", "yes");
        ObjectNode button2 = objectMapper.createObjectNode();
        button2.put("text", "No, thank you");
        button2.put("value", "no");
        buttons.add(button1);
        buttons.add(button2);

        content.set("buttons", buttons);
        card.set("content", content);
        return card;
    }

    private String getSuggestionDescription(String productType) {
        return switch (productType.toLowerCase()) {
            case "laptop" -> "Boost your productivity with a docking station!";
            case "tablet" -> "Enhance your tablet experience with a stylus!";
            case "printer" -> "Keep your printer running with a new ink cartridge!";
            case "touchpad" -> "Complement your touchpad with a wireless mouse!";
            case "ipad" -> "Protect your device with a tablet case!";
            case "computer" -> "Complete your setup with a high-quality monitor!";
            case "p_c" -> "Upgrade your PC with a new keyboard!";
            default -> "Explore more HP accessories to enhance your purchase!";
        };
    }

    private String getSuggestedURL(String productType) {
        return switch (productType.toLowerCase()) {
            case "laptop" -> "https://shorturl.at/cIPgM";
            case "tablet" -> "https://shorturl.at/Y2bFu";
            case "printer" -> "https://shorturl.at/rr0Wg";
            case "touchpad" -> "https://shorturl.at/lMRaS";
            case "ipad" -> "https://tinyurl.com/3hvhuukb";
            case "computer" -> "https://tinyurl.com/muru24v4";
            case "p_c" -> "https://tinyurl.com/35ue6py8";
            default -> "https://tinyurl.com/3y8wx23v";
        };
    }

}