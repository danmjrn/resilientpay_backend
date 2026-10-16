package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.mock.env.MockEnvironment;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.ActorContextFactory;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.OperationPolicyController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.OriginalTransactionController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.ReconciliationController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.ReversalCommandController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.WebCommandMapper;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security.DemoTokenController;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.ReversalOperations;

/**
 * Verifies that worker-only processes do not instantiate the HTTP application surface.
 *
 * @author Daniel Jr Nkulu
 */
class ApiRuntimeRoleConditionTest {
    private static final List<Class<?>> API_CONTROLLERS = List.of(
            ReversalCommandController.class,
            OriginalTransactionController.class,
            OperationPolicyController.class,
            ReconciliationController.class,
            DemoTokenController.class);

    @Test
    void everyApiControllerRequiresTheExactApiRole() {
        for (Class<?> controller : API_CONTROLLERS) {
            ConditionalOnRuntimeRole condition =
                    AnnotationUtils.findAnnotation(controller, ConditionalOnRuntimeRole.class);
            assertEquals("api", condition.value());
        }
    }

    @Test
    void workerOnlyOmitsControllersWhileApiRoleCreatesThem() {
        runner("worker").run(context -> {
            assertTrue(context.getBeansOfType(ReversalCommandController.class).isEmpty());
            assertTrue(
                    context.getBeansOfType(OriginalTransactionController.class).isEmpty());
            assertTrue(context.getBeansOfType(OperationPolicyController.class).isEmpty());
            assertTrue(context.getBeansOfType(ReconciliationController.class).isEmpty());
        });
        runner("worker, api").run(context -> {
            assertFalse(context.getBeansOfType(ReversalCommandController.class).isEmpty());
            assertFalse(
                    context.getBeansOfType(OriginalTransactionController.class).isEmpty());
            assertFalse(context.getBeansOfType(OperationPolicyController.class).isEmpty());
            assertFalse(context.getBeansOfType(ReconciliationController.class).isEmpty());
        });
    }

    private static ApplicationContextRunner runner(String roles) {
        ReversalOperations operations = (ReversalOperations) Proxy.newProxyInstance(
                ReversalOperations.class.getClassLoader(),
                new Class<?>[] {ReversalOperations.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() != Object.class) {
                        return null;
                    }
                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        case "toString" -> "ReversalOperations test proxy";
                        default -> throw new AssertionError("Unexpected Object method: " + method.getName());
                    };
                });
        return new ApplicationContextRunner()
                .withPropertyValues("resilientpay.runtime.roles=" + roles)
                .withBean(ReversalOperations.class, () -> operations)
                .withBean(ActorContextFactory.class, ActorContextFactory::new)
                .withBean(WebCommandMapper.class, () -> new WebCommandMapper(new MockEnvironment()))
                .withUserConfiguration(
                        ReversalCommandController.class,
                        OriginalTransactionController.class,
                        OperationPolicyController.class,
                        ReconciliationController.class);
    }
}
