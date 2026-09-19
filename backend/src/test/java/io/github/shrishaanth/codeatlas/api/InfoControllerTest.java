package io.github.shrishaanth.codeatlas.api;

import io.github.shrishaanth.codeatlas.config.CodeAtlasProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InfoController.class)
@EnableConfigurationProperties(CodeAtlasProperties.class)
@TestPropertySource(properties = "codeatlas.version=1.2.3")
class InfoControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void reportsNameVersionAndParsedLanguages() throws Exception {
        mvc.perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("CodeAtlas"))
                .andExpect(jsonPath("$.version").value("1.2.3"))
                .andExpect(jsonPath("$.parsedLanguages[0]").value("python"));
    }
}
