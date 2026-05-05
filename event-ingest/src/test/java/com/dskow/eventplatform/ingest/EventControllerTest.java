package com.dskow.eventplatform.ingest;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dskow.eventplatform.ingest.api.EventController;
import com.dskow.eventplatform.ingest.kafka.EventProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EventController.class)
class EventControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    EventProducer producer;

    @Test
    void acceptsValidEventAndAssignsIdAndTimestamp() throws Exception {
        String body = """
            {
              "assetId": "asset-1",
              "latitude": 35.7,
              "longitude": -78.6,
              "status": "in-transit"
            }
            """;

        mvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.assetId").value("asset-1"));

        verify(producer).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void usesIdempotencyKeyHeaderAsEventIdWhenBodyIdIsAbsent() throws Exception {
        String body = """
            {
              "assetId": "asset-1",
              "latitude": 35.7,
              "longitude": -78.6,
              "status": "in-transit"
            }
            """;

        mvc.perform(post("/api/events")
                .header("Idempotency-Key", "client-supplied-id-42")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.id").value("client-supplied-id-42"));
    }

    @Test
    void bodyIdWinsOverIdempotencyKeyHeader() throws Exception {
        String body = """
            {
              "id": "body-id",
              "assetId": "asset-1",
              "latitude": 0,
              "longitude": 0,
              "status": "x"
            }
            """;

        mvc.perform(post("/api/events")
                .header("Idempotency-Key", "header-id")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.id").value("body-id"));
    }

    @Test
    void rejectsMissingAssetId() throws Exception {
        String body = """
            {
              "latitude": 0.0,
              "longitude": 0.0,
              "status": "unknown"
            }
            """;

        mvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsLatitudeOutOfRange() throws Exception {
        String body = """
            {
              "assetId": "asset-1",
              "latitude": 91.0,
              "longitude": 0.0,
              "status": "in-transit"
            }
            """;
        mvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsLongitudeOutOfRange() throws Exception {
        String body = """
            {
              "assetId": "asset-1",
              "latitude": 0.0,
              "longitude": 181.0,
              "status": "in-transit"
            }
            """;
        mvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsStatusWithUppercaseOrSpecialChars() throws Exception {
        String body = """
            {
              "assetId": "asset-1",
              "latitude": 0.0,
              "longitude": 0.0,
              "status": "In-Transit!"
            }
            """;
        mvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAssetIdWithDisallowedChars() throws Exception {
        String body = """
            {
              "assetId": "asset@1",
              "latitude": 0.0,
              "longitude": 0.0,
              "status": "in-transit"
            }
            """;
        mvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsIdempotencyKeyWithNewline() throws Exception {
        String body = """
            {
              "assetId": "asset-1",
              "latitude": 0.0,
              "longitude": 0.0,
              "status": "in-transit"
            }
            """;
        mvc.perform(post("/api/events")
                .header("Idempotency-Key", "abc\nINJECTED")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsIdempotencyKeyOverLengthCap() throws Exception {
        String body = """
            {
              "assetId": "asset-1",
              "latitude": 0.0,
              "longitude": 0.0,
              "status": "in-transit"
            }
            """;
        String oversizedKey = "k".repeat(129);
        mvc.perform(post("/api/events")
                .header("Idempotency-Key", oversizedKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }
}
