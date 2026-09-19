package io.github.shrishaanth.codeatlas.api;

import io.github.shrishaanth.codeatlas.config.CodeAtlasProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Tells the frontend which backend it is talking to and what it can analyze. */
@RestController
@RequestMapping("/api")
public class InfoController {

    public record InfoResponse(String name, String version, List<String> parsedLanguages) {
    }

    private final CodeAtlasProperties properties;

    public InfoController(CodeAtlasProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/info")
    public InfoResponse info() {
        return new InfoResponse("CodeAtlas", properties.version(), List.of("python"));
    }
}
