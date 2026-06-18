package dev.scaffoldkit.fifa.betfair.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.scaffoldkit.fifa.betfair.model.BetfairMarketCatalog;
import dev.scaffoldkit.fifa.betfair.model.BetfairMarketBook;
import dev.scaffoldkit.fifa.betfair.model.BetfairOddsSnapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom deserializer for BetfairOddsSnapshot using JsonNode.
 * This deserializes the combined snapshot stored in fallback-odds.json.
 */
public class BetfairOddsSnapshotDeserializer extends JsonDeserializer<BetfairOddsSnapshot> {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public BetfairOddsSnapshot deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        
        // Deserialize catalogue array
        List<BetfairMarketCatalog> catalogue = deserializeCatalogueArray(node.path("catalogue"));
        
        // Deserialize books array
        List<BetfairMarketBook> books = deserializeBooksArray(node.path("books"));
        
        // Get snapshot timestamp
        String snapshotTimestamp = node.path("snapshotTimestamp").asText("");
        
        return new BetfairOddsSnapshot(catalogue, books, snapshotTimestamp);
    }
    
    private List<BetfairMarketCatalog> deserializeCatalogueArray(JsonNode arrayNode) {
        if (!arrayNode.isArray() || arrayNode.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<BetfairMarketCatalog> result = new ArrayList<>();
        for (JsonNode itemNode : arrayNode) {
            String marketId = itemNode.path("marketId").asText("");
            String marketName = itemNode.path("marketName").asText("");
            String marketStartTime = itemNode.path("marketStartTime").asText("");
            double totalMatched = itemNode.path("totalMatched").asDouble(0.0);
            
            List<dev.scaffoldkit.fifa.betfair.model.BetfairRunnerCatalog> runners = 
                    deserializeRunners(itemNode.path("runners"));
            
            dev.scaffoldkit.fifa.betfair.model.BetfairEventType eventType = 
                    deserializeEventType(itemNode.path("eventType"));
            dev.scaffoldkit.fifa.betfair.model.BetfairCompetition competition = 
                    deserializeCompetition(itemNode.path("competition"));
            dev.scaffoldkit.fifa.betfair.model.BetfairEvent event = 
                    deserializeEvent(itemNode.path("event"));
            
            result.add(new BetfairMarketCatalog(marketId, marketName, marketStartTime, totalMatched,
                    runners, eventType, competition, event));
        }
        return result;
    }
    
    private List<BetfairMarketBook> deserializeBooksArray(JsonNode arrayNode) {
        if (!arrayNode.isArray() || arrayNode.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<BetfairMarketBook> result = new ArrayList<>();
        for (JsonNode itemNode : arrayNode) {
            String marketId = itemNode.path("marketId").asText("");
            boolean isMarketDataDelayed = itemNode.path("isMarketDataDelayed").asBoolean(false);
            String status = itemNode.path("status").asText("");
            int betDelay = itemNode.path("betDelay").asInt(0);
            boolean bspReconciled = itemNode.path("bspReconciled").asBoolean(false);
            boolean complete = itemNode.path("complete").asBoolean(false);
            boolean inplay = itemNode.path("inplay").asBoolean(false);
            int numberOfWinners = itemNode.path("numberOfWinners").asInt(0);
            int numberOfRunners = itemNode.path("numberOfRunners").asInt(0);
            int numberOfActiveRunners = itemNode.path("numberOfActiveRunners").asInt(0);
            String lastMatchTime = itemNode.path("lastMatchTime").asText("");
            double totalMatched = itemNode.path("totalMatched").asDouble(0.0);
            double totalAvailable = itemNode.path("totalAvailable").asDouble(0.0);
            boolean crossMatching = itemNode.path("crossMatching").asBoolean(false);
            boolean runnersVoidable = itemNode.path("runnersVoidable").asBoolean(false);
            long version = itemNode.path("version").asLong(0);
            
            List<dev.scaffoldkit.fifa.betfair.model.BetfairRunnerBook> runners = 
                    deserializeBookRunners(itemNode.path("runners"));
            
            result.add(new BetfairMarketBook(
                    marketId, isMarketDataDelayed, status, betDelay, bspReconciled, complete,
                    inplay, numberOfWinners, numberOfRunners, numberOfActiveRunners, lastMatchTime,
                    totalMatched, totalAvailable, crossMatching, runnersVoidable, version, runners
            ));
        }
        return result;
    }
    
    private List<dev.scaffoldkit.fifa.betfair.model.BetfairRunnerCatalog> deserializeRunners(JsonNode runnersNode) {
        if (!runnersNode.isArray() || runnersNode.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<dev.scaffoldkit.fifa.betfair.model.BetfairRunnerCatalog> result = new ArrayList<>();
        for (JsonNode runnerNode : runnersNode) {
            long selectionId = runnerNode.path("selectionId").asLong(0);
            String runnerName = runnerNode.path("runnerName").asText("");
            double handicap = runnerNode.path("handicap").asDouble(0.0);
            int sortPriority = runnerNode.path("sortPriority").asInt(0);
            result.add(new dev.scaffoldkit.fifa.betfair.model.BetfairRunnerCatalog(
                    selectionId, runnerName, handicap, sortPriority));
        }
        return result;
    }
    
    private List<dev.scaffoldkit.fifa.betfair.model.BetfairRunnerBook> deserializeBookRunners(JsonNode runnersNode) {
        if (!runnersNode.isArray() || runnersNode.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<dev.scaffoldkit.fifa.betfair.model.BetfairRunnerBook> result = new ArrayList<>();
        for (JsonNode runnerNode : runnersNode) {
            long selectionId = runnerNode.path("selectionId").asLong(0);
            double handicap = runnerNode.path("handicap").asDouble(0.0);
            String status = runnerNode.path("status").asText("");
            double lastPriceTraded = runnerNode.path("lastPriceTraded").asDouble(0.0);
            double totalMatched = runnerNode.path("totalMatched").asDouble(0.0);
            
            dev.scaffoldkit.fifa.betfair.model.BetfairExchangePrices ex = null;
            JsonNode exNode = runnerNode.path("ex");
            if (!exNode.isMissingNode() && !exNode.isNull()) {
                ex = deserializeExchangePrices(exNode);
            }
            
            result.add(new dev.scaffoldkit.fifa.betfair.model.BetfairRunnerBook(
                    selectionId, handicap, status, lastPriceTraded, totalMatched, ex));
        }
        return result;
    }
    
    private dev.scaffoldkit.fifa.betfair.model.BetfairExchangePrices deserializeExchangePrices(JsonNode exNode) {
        var backArray = exNode.path("availableToBack");
        var layArray = exNode.path("availableToLay");
        var volumeArray = exNode.path("tradedVolume");
        
        var availableToBack = deserializePriceSizeArray(backArray);
        var availableToLay = deserializePriceSizeArray(layArray);
        var tradedVolume = deserializePriceSizeArray(volumeArray);
        
        return new dev.scaffoldkit.fifa.betfair.model.BetfairExchangePrices(
                availableToBack, availableToLay, tradedVolume);
    }
    
    private List<dev.scaffoldkit.fifa.betfair.model.BetfairPriceSize> deserializePriceSizeArray(JsonNode arrayNode) {
        if (!arrayNode.isArray() || arrayNode.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<dev.scaffoldkit.fifa.betfair.model.BetfairPriceSize> result = new ArrayList<>();
        for (JsonNode itemNode : arrayNode) {
            double price = itemNode.path("price").asDouble(0.0);
            double size = itemNode.path("size").asDouble(0.0);
            result.add(new dev.scaffoldkit.fifa.betfair.model.BetfairPriceSize(price, size));
        }
        return result;
    }
    
    private dev.scaffoldkit.fifa.betfair.model.BetfairEventType deserializeEventType(JsonNode eventTypeNode) {
        if (eventTypeNode.isMissingNode() || eventTypeNode.isNull()) {
            return null;
        }
        String id = eventTypeNode.path("id").asText("");
        String name = eventTypeNode.path("name").asText("");
        return new dev.scaffoldkit.fifa.betfair.model.BetfairEventType(id, name);
    }
    
    private dev.scaffoldkit.fifa.betfair.model.BetfairCompetition deserializeCompetition(JsonNode competitionNode) {
        if (competitionNode.isMissingNode() || competitionNode.isNull()) {
            return null;
        }
        String id = competitionNode.path("id").asText("");
        String name = competitionNode.path("name").asText("");
        return new dev.scaffoldkit.fifa.betfair.model.BetfairCompetition(id, name);
    }
    
    private dev.scaffoldkit.fifa.betfair.model.BetfairEvent deserializeEvent(JsonNode eventNode) {
        if (eventNode.isMissingNode() || eventNode.isNull()) {
            return null;
        }
        String id = eventNode.path("id").asText("");
        String name = eventNode.path("name").asText("");
        String timezone = eventNode.path("timezone").asText("");
        String openDate = eventNode.path("openDate").asText("");
        return new dev.scaffoldkit.fifa.betfair.model.BetfairEvent(id, name, timezone, openDate);
    }
}