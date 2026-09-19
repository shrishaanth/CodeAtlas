package io.github.shrishaanth.codeatlas.pipeline;

/** Receives progress while an analysis runs. Percent is 0..100 across all stages. */
@FunctionalInterface
public interface ProgressListener {

    ProgressListener NONE = (stage, percent, detail) -> { };

    void onProgress(String stage, int percent, String detail);
}
