package dev.scaffoldkit.fifa.betfair.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import dev.scaffoldkit.fifa.betfair.model.BetfairPriceSize;

import java.io.IOException;

/**
 * Custom deserializer for BetfairPriceSize using JsonNode.
 */
public class BetfairPriceSizeDeserializer extends JsonDeserializer<BetfairPriceSize> {

    @Override
    public BetfairPriceSize deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        
        double price = node.path("price").asDouble(0.0);
        double size = node.path("size").asDouble(0.0);
        
        return new BetfairPriceSize(price, size);
    }
}