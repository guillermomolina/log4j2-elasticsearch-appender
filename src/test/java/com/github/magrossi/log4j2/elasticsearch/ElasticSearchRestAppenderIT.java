package com.github.magrossi.log4j2.elasticsearch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ElasticSearchRestAppenderIT {

    @Test
    public void integrationTest() throws IOException {
        String test = "integration-test";
        Logger logger = getLogger(test);
        ElasticClient client = new ElasticClient(logger);
        String marker = getUniqueMarker();
        logger.error(MarkerManager.getMarker(marker), "Test Message");
        JsonNode doc = client.findFirstByMarker(marker);
        assertNotNull(doc);
        assertFieldValue(doc, "level", "ERROR");
        assertFieldValue(doc, "message", "Test Message");
    }

    @Test
    public void integrationTestAfter5() throws IOException {
        String test = "integration-test-after-5";
        Logger logger = getLogger(test);
        ElasticClient client = new ElasticClient(logger);
        String marker = getUniqueMarker();

        // Should not send until it reaches 5 events
        for (int i = 1; i < 5; i++) {
            logger.error(MarkerManager.getMarker(marker), i + "-" + test);
        }
        JsonNode doc = client.findFirstByMarker(marker);
        assertNull(doc);

        // But after the 5th event it should send all buffered events
        logger.error(MarkerManager.getMarker(marker), "5-" + test);
        JsonNode hits = client.findAllByMarker(marker);
        assertNotNull(hits);
        assertEquals(5, hits.size());
        for (int i = 0; i < 5; i++) {
            assertFieldValue(hits.get(i).get("_source"), "level", "ERROR");
            assertFieldValue(hits.get(i).get("_source"), "message", (i + 1) + "-" + test);
        }
    }

    private Logger getLogger(String appender) {
        final LoggerContext loggerContext = Configurator.initialize(UUID.randomUUID().toString(), "src/test/resources/test.xml");
        Logger logger = loggerContext.getLogger(appender);
        logger.getAppenders().clear();
        logger.addAppender(loggerContext.getConfiguration().getAppenders().get(appender));
        return logger;
    }

    private String getUniqueMarker() {
        return UUID.randomUUID().toString();
    }

    private static void assertFieldValue(JsonNode node, String fieldName, Object expected) {
        JsonNode field = node.get(fieldName);
        assertNotNull(field);
        assertTrue(field.isValueNode());
        if (field.isNull()) {
            assertNull(expected);
        } else if (field.isTextual()) {
            assertEquals(expected, field.asText());
        } else if (field.isNumber()) {
            assertEquals(expected, field.numberValue());
        } else if (field.isBoolean()) {
            assertEquals(expected, field.asBoolean());
        } else {
            assertEquals(expected.toString(), field.toString());
        }
    }


    /**
     * Helper Elastic client for finding logs
     */
    public static class ElasticClient {

        private static Map<String,String> parms = new HashMap<>();
        private static Header[] headers = new Header[] { new BasicHeader("Content-Type", "application/json") };

        private final RestClient client;
        private final String index;
        private final String type;

        ElasticClient(Logger logger) {
            Map<String, Appender> appenders = logger.getAppenders();
            ElasticSearchRestAppender appender = (ElasticSearchRestAppender)appenders.get(appenders.keySet().iterator().next());
            client = RestClient.builder(new HttpHost("elasticsearch", 9200)).build();
            index = appender.getIndex();
            type = appender.getType();
        }

        private JsonNode query(String query) throws IOException {
            // Refresh the index first
            client.performRequest("GET", String.format("%s*/_refresh", index), headers);

            // Then query for results
            Response response = client.performRequest("GET", String.format("%s*/%s/_search", index, type), parms, new NStringEntity(query), headers);
            String body = EntityUtils.toString(response.getEntity());

            // Convert to TreeNode and position at { hits.hits: [..] }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode result = mapper.readTree(body);
            return result.get("hits").get("hits");
        }

        JsonNode findAllByMarker(String marker) throws IOException {
            return this.query(String.format("{\"query\": {\"match\": {\"marker.name\": \"%s\"}},\"sort\":[{\"message.keyword\":{\"order\":\"asc\"}}]}", marker));
        }

        JsonNode findFirstByMarker(String marker) throws IOException {
            JsonNode hits = this.query(String.format("{\"query\": {\"match\": {\"marker.name\": \"%s\"}},\"size\": 1,\"sort\":[{\"message.keyword\":{\"order\":\"asc\"}}]}", marker));
            assertNotNull(hits);
            if (hits.isArray() && hits.size() > 0) {
                return hits.get(0).get("_source");
            } else {
                return null;
            }
        }
    }

}
