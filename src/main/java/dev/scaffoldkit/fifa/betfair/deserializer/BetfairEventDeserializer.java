package dev.scaffoldkit.fifa.betfair.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import dev.scaffoldkit.fifa.betfair.model.BetfairEvent;

import java.io.IOException;

/**
 * Custom deserializer for BetfairEvent using JsonNode.
 */
public class BetfairEventDeserializer extends JsonDeserializer<BetfairEvent> {

    @Override
    public BetfairEvent deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        
        String id = node.path("id").asText("");
        String name = node.path("name").asText("");
        String timezone = node.path("timezone").asText("");
        String openDate = node.path("openDate").asText("");
        
        return new BetfairEvent(id, name, timezone, openDate);
    }
}