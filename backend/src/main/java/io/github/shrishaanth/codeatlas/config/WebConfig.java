package io.github.shrishaanth.codeatlas.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Allows the separately hosted frontend (e.g. on Vercel) to call the API. */
@Configuration
@EnableConfigurationProperties(CodeAtlasProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final CodeAtlasProperties properties;

    public WebConfig(CodeAtlasProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var origins = properties.cors().allowedOrigins();
        if (origins.isEmpty()) return;
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "DELETE");
    }
}
