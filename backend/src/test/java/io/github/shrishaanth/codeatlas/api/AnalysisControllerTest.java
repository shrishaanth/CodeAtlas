package io.github.shrishaanth.codeatlas.api;

import io.github.shrishaanth.codeatlas.jobs.AnalysisService;
import io.github.shrishaanth.codeatlas.jobs.AnalysisStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalysisController.class)
class AnalysisControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AnalysisService service;

    private static AnalysisStatus queued(UUID id) {
        return new AnalysisStatus(id, "https://github.com/pallets/flask", AnalysisStatus.State.QUEUED, "queued", 0,
                "Waiting to start", null, null, null, Instant.parse("2026-01-01T00:00:00Z"), null, null);
    }

    @Test
    void submitReturns202WithStatus() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.submit("https://github.com/pallets/flask")).thenReturn(queued(id));

        mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repo\":\"https://github.com/pallets/flask\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"));
    }

    @Test
    void invalidRepositoryIsA400WithAReadableMessage() throws Exception {
        when(service.submit(anyString())).thenThrow(new IllegalArgumentException("Only public GitHub repositories are supported"));

        mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repo\":\"https://gitlab.com/a/b\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Only public GitHub repositories are supported"));
    }

    @Test
    void blankRepositoryIsRejectedBeforeReachingTheService() throws Exception {
        mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON).content("{\"repo\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fullQueueIsA503() throws Exception {
        when(service.submit(anyString())).thenThrow(new AnalysisService.QueueFullException());

        mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repo\":\"https://github.com/pallets/flask\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void reportIsServedVerbatimOr404() throws Exception {
        UUID done = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(service.reportJson(done)).thenReturn(Optional.of("{\"schemaVersion\":\"0.1\",\"b\":1,\"a\":2}"));
        when(service.reportJson(missing)).thenReturn(Optional.empty());

        mvc.perform(get("/api/analyses/{id}/report", done))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("{\"schemaVersion\":\"0.1\",\"b\":1,\"a\":2}"));
        mvc.perform(get("/api/analyses/{id}/report", missing)).andExpect(status().isNotFound());
    }
}
