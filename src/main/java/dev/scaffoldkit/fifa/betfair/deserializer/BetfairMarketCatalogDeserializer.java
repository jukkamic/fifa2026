package dev.scaffoldkit.fifa.betfair.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import dev.scaffoldkit.fifa.betfair.model.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom deserializer for BetfairMarketCatalog using JsonNode.
 */
public class BetfairMarketCatalogDeserializer extends JsonDeserializer<BetfairMarketCatalog> {

    @Override
    public BetfairMarketCatalog deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        
        String marketId = node.path("marketId").asText("");
        String marketName = node.path("marketName").asText("");
        String marketStartTime = node.path("marketStartTime").asText("");
        double totalMatched = node.path("totalMatched").asDouble(0.0);
        
        // Deserialize runners array
        List<BetfairRunnerCatalog> runners = deserializeRunners(node.path("runners"));
        
        // Deserialize nested objects
        BetfairEventType eventType = deserializeEventType(node.path("eventType"));
        BetfairCompetition competition = deserializeCompetition(node.path("competition"));
        BetfairEvent event = deserializeEvent(node.path("event"));
        
        return new BetfairMarketCatalog(marketId, marketName, marketStartTime, totalMatched,
                runners, eventType, competition, event);
    }
    
    private List<BetfairRunnerCatalog> deserializeRunners(JsonNode runnersNode) {
        if (!runnersNode.isArray() || runnersNode.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<BetfairRunnerCatalog> result = new ArrayList<>();
        for (JsonNode runnerNode : runnersNode) {
            long selectionId = runnerNode.path("selectionId").asLong(0);
            String runnerName = runnerNode.path("runnerName").asText("");
            double handicap = runnerNode.path("handicap").asDouble(0.0);
            int sortPriority = runnerNode.path("sortPriority").asInt(0);
            result.add(new BetfairRunnerCatalog(selectionId, runnerName, handicap, sortPriority));
        }
        return result;
    }
    
    private BetfairEventType deserializeEventType(JsonNode eventTypeNode) {
        if (eventTypeNode.isMissingNode() || eventTypeNode.isNull()) {
            return null;
        }
        String id = eventTypeNode.path("id").asText("");
        String name = eventTypeNode.path("name").asText("");
        return new BetfairEventType(id, name);
    }
    
    private BetfairCompetition deserializeCompetition(JsonNode competitionNode) {
        if (competitionNode.isMissingNode() || competitionNode.isNull()) {
            return null;
        }
        String id = competitionNode.path("id").asText("");
        String name = competitionNode.path("name").asText("");
        return new BetfairCompetition(id, name);
    }
    
    private BetfairEvent deserializeEvent(JsonNode eventNode) {
        if (eventNode.isMissingNode() || eventNode.isNull()) {
            return null;
        }
        String id = eventNode.path("id").asText("");
        String name = eventNode.path("name").asText("");
        String timezone = eventNode.path("timezone").asText("");
        String openDate = eventNode.path("openDate").asText("");
        return new BetfairEvent(id, name, timezone, openDate);
    }
}