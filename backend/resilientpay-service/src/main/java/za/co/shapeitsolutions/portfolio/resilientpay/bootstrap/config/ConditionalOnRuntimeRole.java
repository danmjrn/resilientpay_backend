package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Activates a component only when its exact runtime role is selected.
 *
 * @author Daniel Jr Nkulu
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(RuntimeRoleCondition.class)
public @interface ConditionalOnRuntimeRole {
    /**
     * Returns the required exact lowercase role.
     *
     * @return required runtime role
     */
    String value();
}
