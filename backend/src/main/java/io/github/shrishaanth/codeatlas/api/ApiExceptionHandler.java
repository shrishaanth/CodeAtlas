package io.github.shrishaanth.codeatlas.api;

import io.github.shrishaanth.codeatlas.jobs.AnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns expected errors into RFC 9457 problem responses with a message the UI can show as is. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badInput(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(AnalysisService.QueueFullException.class)
    public ProblemDetail busy(AnalysisService.QueueFullException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(io.github.shrishaanth.codeatlas.qa.QaService.TooManyQuestionsException.class)
    public ProblemDetail tooManyQuestions(io.github.shrishaanth.codeatlas.qa.QaService.TooManyQuestionsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }
}
