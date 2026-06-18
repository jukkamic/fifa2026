package dev.scaffoldkit.fifa.betfair.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import dev.scaffoldkit.fifa.betfair.model.BetfairExchangePrices;
import dev.scaffoldkit.fifa.betfair.model.BetfairPriceSize;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom deserializer for BetfairExchangePrices using JsonNode.
 */
public class BetfairExchangePricesDeserializer extends JsonDeserializer<BetfairExchangePrices> {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public BetfairExchangePrices deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        
        List<BetfairPriceSize> availableToBack = deserializePriceArray(node.path("availableToBack"));
        List<BetfairPriceSize> availableToLay = deserializePriceArray(node.path("availableToLay"));
        List<BetfairPriceSize> tradedVolume = deserializePriceArray(node.path("tradedVolume"));
        
        return new BetfairExchangePrices(availableToBack, availableToLay, tradedVolume);
    }
    
    private List<BetfairPriceSize> deserializePriceArray(JsonNode arrayNode) {
        if (!arrayNode.isArray() || arrayNode.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<BetfairPriceSize> result = new ArrayList<>();
        for (JsonNode itemNode : arrayNode) {
            double price = itemNode.path("price").asDouble(0.0);
            double size = itemNode.path("size").asDouble(0.0);
            result.add(new BetfairPriceSize(price, size));
        }
        return result;
    }
}