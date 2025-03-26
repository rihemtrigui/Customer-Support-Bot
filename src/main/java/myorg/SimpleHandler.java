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

public class SimpleHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private static final String TABLE_NAME = "Clients_Database";

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

            if (!orderExists(orderNumber)) {
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

            return buildLexResponse(intentName, "How can I help you with your order ?", "InProgress", sessionAttributesMap, "ActionType");

        } catch (Exception e) {
            context.getLogger().log("Error processing Lex event: " + e.getMessage());
            return buildLexResponse("UnknownIntent", "Error processing request. Please try again later.", "Failed", Map.of(), null);
        }
    }

    private String getOrRestoreSlot(String slotName, Map<String, Object> slots, Map<String, Object> sessionAttributesMap, Context context) {
        String slotValue = null;
        if (slots.get(slotName) != null) {
            JsonNode slotNode = new ObjectMapper().valueToTree(slots.get(slotName));
            if (slotNode.has("value") && slotNode.path("value").has("interpretedValue")) {
                slotValue = slotNode.path("value").path("interpretedValue").asText();
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

        if (slotToElicit != null && (
                slotToElicit.equals("OrderNumber") ||
                        slotToElicit.equals("ActionType") ||
                        slotToElicit.equals("ShippingAddress"))) {

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

    private boolean orderExists(int orderNumber) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("order_number", AttributeValue.builder().n(String.valueOf(orderNumber)).build()))
                .build();
        GetItemResponse response = dynamoDbClient.getItem(request);
        return response.hasItem();
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
}