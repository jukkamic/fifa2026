package dev.scaffoldkit.fifa.betfair.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import dev.scaffoldkit.fifa.betfair.model.BetfairRunnerBook;
import dev.scaffoldkit.fifa.betfair.model.BetfairExchangePrices;
import dev.scaffoldkit.fifa.betfair.model.BetfairPriceSize;
import dev.scaffoldkit.fifa.betfair.model.BetfairMarketBook;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom deserializer for BetfairMarketBook using JsonNode.
 */
public class BetfairMarketBookDeserializer extends JsonDeserializer<BetfairMarketBook> {

    @Override
    public BetfairMarketBook deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        
        String marketId = node.path("marketId").asText("");
        boolean isMarketDataDelayed = node.path("isMarketDataDelayed").asBoolean(false);
        String status = node.path("status").asText("");
        int betDelay = node.path("betDelay").asInt(0);
        boolean bspReconciled = node.path("bspReconciled").asBoolean(false);
        boolean complete = node.path("complete").asBoolean(false);
        boolean inplay = node.path("inplay").asBoolean(false);
        int numberOfWinners = node.path("numberOfWinners").asInt(0);
        int numberOfRunners = node.path("numberOfRunners").asInt(0);
        int numberOfActiveRunners = node.path("numberOfActiveRunners").asInt(0);
        String lastMatchTime = node.path("lastMatchTime").asText("");
        double totalMatched = node.path("totalMatched").asDouble(0.0);
        double totalAvailable = node.path("totalAvailable").asDouble(0.0);
        boolean crossMatching = node.path("crossMatching").asBoolean(false);
        boolean runnersVoidable = node.path("runnersVoidable").asBoolean(false);
        long version = node.path("version").asLong(0);
        
        // Deserialize runners array
        List<BetfairRunnerBook> runners = deserializeRunners(node.path("runners"));
        
        return new BetfairMarketBook(
                marketId,
                isMarketDataDelayed,
                status,
                betDelay,
                bspReconciled,
                complete,
                inplay,
                numberOfWinners,
                numberOfRunners,
                numberOfActiveRunners,
                lastMatchTime,
                totalMatched,
                totalAvailable,
                crossMatching,
                runnersVoidable,
                version,
                runners
        );
    }
    
    private List<BetfairRunnerBook> deserializeRunners(JsonNode runnersNode) {
        if (!runnersNode.isArray() || runnersNode.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<BetfairRunnerBook> result = new ArrayList<>();
        for (JsonNode runnerNode : runnersNode) {
            long selectionId = runnerNode.path("selectionId").asLong(0);
            double handicap = runnerNode.path("handicap").asDouble(0.0);
            String status = runnerNode.path("status").asText("");
            double lastPriceTraded = runnerNode.path("lastPriceTraded").asDouble(0.0);
            double totalMatched = runnerNode.path("totalMatched").asDouble(0.0);
            
            // Deserialize nested BetfairExchangePrices object
            BetfairExchangePrices ex = null;
            JsonNode exNode = runnerNode.path("ex");
            if (!exNode.isMissingNode() && !exNode.isNull()) {
                ex = deserializeExchangePrices(exNode);
            }
            
            result.add(new BetfairRunnerBook(selectionId, handicap, status, lastPriceTraded, totalMatched, ex));
        }
        return result;
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
    
    private List<BetfairPriceSize> deserializePriceSizeArray(JsonNode arrayNode) {
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