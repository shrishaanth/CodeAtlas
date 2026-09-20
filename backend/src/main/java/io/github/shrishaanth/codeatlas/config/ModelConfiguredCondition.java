package io.github.shrishaanth.codeatlas.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True only when {@code codeatlas.qa.model} has a real value. {@code @ConditionalOnProperty} treats an
 * empty value as configured, which would build a client with no model whenever the environment
 * variable is unset (its default is empty).
 */
public class ModelConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String model = context.getEnvironment().getProperty("codeatlas.qa.model");
        return model != null && !model.isBlank();
    }
}
