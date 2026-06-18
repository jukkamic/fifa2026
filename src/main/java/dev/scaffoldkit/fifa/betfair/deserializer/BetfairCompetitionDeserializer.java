package dev.scaffoldkit.fifa.betfair.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import dev.scaffoldkit.fifa.betfair.model.BetfairCompetition;

import java.io.IOException;

/**
 * Custom deserializer for BetfairCompetition using JsonNode.
 */
public class BetfairCompetitionDeserializer extends JsonDeserializer<BetfairCompetition> {

    @Override
    public BetfairCompetition deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        
        String id = node.path("id").asText("");
        String name = node.path("name").asText("");
        
        return new BetfairCompetition(id, name);
    }
}