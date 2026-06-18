package dev.scaffoldkit.fifa.betfair.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import dev.scaffoldkit.fifa.betfair.model.BetfairRunnerBook;
import dev.scaffoldkit.fifa.betfair.model.BetfairExchangePrices;

import java.io.IOException;

/**
 * Custom deserializer for BetfairRunnerBook using JsonNode.
 */
public class BetfairRunnerBookDeserializer extends JsonDeserializer<BetfairRunnerBook> {

    @Override
    public BetfairRunnerBook deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        
        long selectionId = node.path("selectionId").asLong(0);
        double handicap = node.path("handicap").asDouble(0.0);
        String status = node.path("status").asText("");
        double lastPriceTraded = node.path("lastPriceTraded").asDouble(0.0);
        double totalMatched = node.path("totalMatched").asDouble(0.0);
        
        // Deserialize nested BetfairExchangePrices object
        BetfairExchangePrices ex = null;
        JsonNode exNode = node.path("ex");
        if (!exNode.isMissingNode() && !exNode.isNull()) {
            ex = deserializeExchangePrices(exNode);
        }
        
        return new BetfairRunnerBook(selectionId, handicap, status, lastPriceTraded, totalMatched, ex);
    }
    
    private BetfairExchangePrices deserializeExchangePrices(JsonNode exNode) {
        var backArray = exNode.path("availableToBack");
        var layArray = exNode.path("availableToLay");
        var volumeArray = exNode.path("tradedVolume");
        
        var availableToBack = deserializePriceSizeArray(backArray);
        var availableToLay = deserializePriceSizeArray(layArray);
        var tradedVolume = deserializePriceSizeArray(volumeArray);
        
        return new BetfairExchangePrices(availableToBack, availableToLay, tradedVolume);
    }
    
    private java.util.List<dev.scaffoldkit.fifa.betfair.model.BetfairPriceSize> deserializePriceSizeArray(JsonNode arrayNode) {
        if (!arrayNode.isArray() || arrayNode.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        
        var result = new java.util.ArrayList<dev.scaffoldkit.fifa.betfair.model.BetfairPriceSize>();
        for (JsonNode itemNode : arrayNode) {
            double price = itemNode.path("price").asDouble(0.0);
            double size = itemNode.path("size").asDouble(0.0);
            result.add(new dev.scaffoldkit.fifa.betfair.model.BetfairPriceSize(price, size));
        }
        return result;
    }
}