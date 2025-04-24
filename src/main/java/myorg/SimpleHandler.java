package myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.Map;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;


public class SimpleHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private static final String TABLE_NAME = "Clients_Database";
    private static final String ALGOBOOK_API_KEY = "6617961216msh17b4859478138bcp17d8f3jsn9ca072fb9a3d"; // Replace with your RapidAPI key
    private static final String ALGOBOOK_API_HOST = "credit-card-validation-api-algobook.p.rapidapi.com";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            JsonNode rootNode = objectMapper.valueToTree(event);
            JsonNode sessionState = rootNode.path("sessionState");
            JsonNode intent = sessionState.path("intent");
            String intentName = intent.has("name") ? intent.path("name").asText() : "UnknownIntent";
            JsonNode slotsNode = intent.path("slots");
            Map<String, Object> slots = objectMapper.convertValue(slotsNode, new TypeReference<Map<String, Object>>() {});
            JsonNode sessionAttributes = rootNode.path("sessionAttributes");
            Map<String, Object> sessionAttributesMap = new HashMap<>();
            if (sessionAttributes != null && !sessionAttributes.isMissingNode()) {
                sessionAttributesMap = objectMapper.convertValue(sessionAttributes, new TypeReference<Map<String, Object>>() {});
            }
            context.getLogger().log("Session Attributes: " + sessionAttributesMap);

            for (String slotName : slots.keySet()) {
                String slotValue = getOrRestoreSlot(slotName, slots, sessionAttributesMap, context);
                if (slotValue != null && !slotValue.isEmpty()) {
                    sessionAttributesMap.put(slotName, slotValue);
                }
            }
            if ("OrderHPItemIntent".equals(intentName)) {
                return handleOrderHPItemIntent(intentName, slots, sessionAttributesMap, context);
            } else if ("ChangeOrderIntent".equals(intentName)) {
                return handleChangeOrderIntent(intentName, slots, sessionAttributesMap, context);
            } else if ("GreetingsIntent".equals(intentName)) {
                return handleGreetingsIntent(intentName, slots, sessionAttributesMap, context);
            } else {
                return handleFallBackIntent(intentName, slots, sessionAttributesMap, context);
            }
        } catch (Exception e) {
            context.getLogger().log("Error processing Lex event: " + e.getMessage());
            return buildLexResponse("UnknownIntent", "Error processing request. Please try again later.", "Failed", Map.of(), null);
        }
    }
    private Map<String, Object> handleGreetingsIntent(String intentName, Map<String, Object> slots, Map<String, Object> sessionAttributesMap, Context context) {
        String greetingMessage = "Hello ! Welcome to HP SmartBot ! How may I help you today ?";
        return buildLexResponse(intentName, greetingMessage, "Fulfilled", sessionAttributesMap, null);
    }
    private Map<String, Object> handleFallBackIntent(String intentName, Map<String, Object> slots, Map<String, Object> sessionAttributesMap, Context context) {
        String errorMessage = "I'm sorry, I couldn't understand your request. Could you please clarify your message ?";
        return buildLexResponse(intentName, errorMessage, "Fulfilled", sessionAttributesMap, null);
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
            return buildLexResponse(intentName, "How would you like to pay? You can choose between cash on delivery or online payment with a card.", "InProgress", sessionAttributesMap, "PaymentMethod");
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

        String confirmationMessage = String.format(
                "Your order has been successfully placed ! Your order number is #%d ",
                orderNumber
        );
        return buildLexResponse(intentName, confirmationMessage, "Fulfilled", sessionAttributesMap, null);
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
            String paymentMethod = orderItem.get("payment method").s();
            context.getLogger().log("Processing update_payment for payment method: " + paymentMethod);
            if (paymentMethod.equalsIgnoreCase("online")) {
                return buildLexResponse(intentName, "The order with number " + orderNumber + " has already been paid online.", "Fulfilled", sessionAttributesMap, null);
            } else if (paymentMethod.equalsIgnoreCase("on_shipment")) {
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
                slotToElicit.equals("Products") || slotToElicit.equals("ProductName") || slotToElicit.equals("ProductNumber") || slotToElicit.equals("Name")
                || slotToElicit.equals("Email"))) {
            dialogAction.put("type", "ElicitSlot");
            dialogAction.put("slotToElicit", slotToElicit);
        } else {
            dialogAction.put("type", "Close");
        }

        Map<String, Object> slots = new HashMap<>();
        for (String key : sessionAttributes.keySet()) {
            slots.put(key, Map.of("value", Map.of("interpretedValue", sessionAttributes.get(key))));
        }

        return Map.of(
                "sessionState", Map.of(
                        "dialogAction", dialogAction,
                        "intent", Map.of(
                                "name", intentName,
                                "state", intentState,
                                "slots", slots
                        ),
                        "sessionAttributes", sessionAttributes
                ),
                "messages", new Object[]{
                        Map.of("contentType", "PlainText", "content", message)
                }
        );
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

    private void updatePaymentMethod(int orderNumber, String newPaymentMethod) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("order_number", AttributeValue.builder().n(String.valueOf(orderNumber)).build());

        Map<String, AttributeValue> attributeValues = new HashMap<>();
        attributeValues.put(":newPaymentMethod", AttributeValue.builder().s(newPaymentMethod).build());

        Map<String, String> expressionAttributeNames = new HashMap<>();
        expressionAttributeNames.put("#pm", "payment method");

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .updateExpression("SET #pm = :newPaymentMethod")
                .expressionAttributeValues(attributeValues)
                .expressionAttributeNames(expressionAttributeNames)
                .build();

        dynamoDbClient.updateItem(request);
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
}