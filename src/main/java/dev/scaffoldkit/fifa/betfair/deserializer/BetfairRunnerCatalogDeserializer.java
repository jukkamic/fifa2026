package dev.scaffoldkit.fifa.betfair.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import dev.scaffoldkit.fifa.betfair.model.BetfairRunnerCatalog;

import java.io.IOException;

/**
 * Custom deserializer for BetfairRunnerCatalog using JsonNode.
 */
public class BetfairRunnerCatalogDeserializer extends JsonDeserializer<BetfairRunnerCatalog> {

    @Override
    public BetfairRunnerCatalog deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        
        long selectionId = node.path("selectionId").asLong(0);
        String runnerName = node.path("runnerName").asText("");
        double handicap = node.path("handicap").asDouble(0.0);
        int sortPriority = node.path("sortPriority").asInt(0);
        
        return new BetfairRunnerCatalog(selectionId, runnerName, handicap, sortPriority);
    }
}