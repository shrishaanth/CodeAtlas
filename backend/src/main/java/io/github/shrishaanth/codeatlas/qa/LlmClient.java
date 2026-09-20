package io.github.shrishaanth.codeatlas.qa;

/** A chat model. One method, so the rest of the code does not care which provider is configured. */
public interface LlmClient {

    /** @return the model's reply, or throws if it could not be reached */
    String complete(String system, String user);

    /** Name of the model, for the report and the UI. */
    String model();
}
