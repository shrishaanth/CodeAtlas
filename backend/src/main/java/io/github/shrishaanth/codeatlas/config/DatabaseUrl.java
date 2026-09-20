package io.github.shrishaanth.codeatlas.config;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accepts the {@code postgres://user:password@host:port/database} form that hosting providers
 * (Render, Heroku, Fly) put in {@code DATABASE_URL}, and turns it into what the JDBC driver needs.
 * A URL that already starts with {@code jdbc:} is left alone.
 */
public final class DatabaseUrl {

    private DatabaseUrl() {
    }

    /**
     * @return properties to apply ({@code spring.datasource.*}), empty when nothing needs changing
     */
    public static Map<String, String> springProperties(String databaseUrl) {
        Map<String, String> out = new LinkedHashMap<>();
        if (databaseUrl == null || databaseUrl.isBlank()) return out;
        String url = databaseUrl.strip();
        if (url.startsWith("jdbc:")) return out;
        if (!url.startsWith("postgres://") && !url.startsWith("postgresql://")) return out;

        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null) return out;
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        String database = uri.getPath() == null ? "" : uri.getPath().replaceAll("^/", "");
        String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
        out.put("spring.datasource.url", "jdbc:postgresql://" + host + ":" + port + "/" + database + query);

        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int colon = userInfo.indexOf(':');
            out.put("spring.datasource.username", colon < 0 ? userInfo : userInfo.substring(0, colon));
            if (colon >= 0) out.put("spring.datasource.password", userInfo.substring(colon + 1));
        }
        return out;
    }
}
