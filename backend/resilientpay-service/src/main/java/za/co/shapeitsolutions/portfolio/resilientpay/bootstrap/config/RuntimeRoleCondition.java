package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import java.util.Arrays;
import java.util.Map;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches exact comma-separated runtime roles without substring or whitespace ambiguity.
 *
 * @author Daniel Jr Nkulu
 */
final class RuntimeRoleCondition implements Condition {
    private static final String DEFAULT_ROLES = "api,outbox-publisher,worker,reconciler";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnRuntimeRole.class.getName());
        if (attributes == null) {
            return false;
        }
        String required = attributes.get("value").toString();
        String configured = context.getEnvironment().getProperty("resilientpay.runtime.roles", DEFAULT_ROLES);
        return Arrays.stream(configured.split(",")).map(String::strip).anyMatch(required::equals);
    }
}
