package za.co.shapeitsolutions.portfolio.resilientpay.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence.SpringTransactionBoundary;

/**
 * Prevents framework and adapter leakage into the core and protects application-owned transaction
 * scopes. Persistence annotations may require an existing scope but must not create business units.
 *
 * @author Daniel Jr Nkulu
 */
class HexagonalArchitectureTest {

    @Test
    void domainAndApplicationRemainFrameworkFree() {
        var classes = new ClassFileImporter()
                .importPackages(
                        "za.co.shapeitsolutions.portfolio.resilientpay.domain",
                        "za.co.shapeitsolutions.portfolio.resilientpay.application");

        noClasses()
                .that()
                .resideInAnyPackage("..domain..", "..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta..", "javax.persistence..")
                .check(classes);

        noClasses()
                .that()
                .resideInAnyPackage("..domain..", "..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..adapter..", "..bootstrap..")
                .check(classes);
    }

    @Test
    void inboundAdaptersNeverDependOnOutboundAdapters() {
        var classes = new ClassFileImporter().importPackages("za.co.shapeitsolutions.portfolio.resilientpay.adapter");

        noClasses()
                .that()
                .resideInAPackage("..adapter.in..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..adapter.out..")
                .check(classes);
    }

    @Test
    void persistenceAnnotationsOnlyRequireApplicationOwnedTransactions() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("za.co.shapeitsolutions.portfolio.resilientpay.adapter");
        int guardedBoundaries = 0;
        for (JavaClass type : classes) {
            if (type.isAnnotatedWith(Transactional.class)) {
                assertEquals(
                        Propagation.MANDATORY,
                        type.getAnnotationOfType(Transactional.class).propagation(),
                        type.getName() + " must not create an adapter-owned transaction");
                guardedBoundaries++;
            }
            for (JavaMethod method : type.getMethods()) {
                if (method.isAnnotatedWith(Transactional.class)) {
                    assertEquals(
                            Propagation.MANDATORY,
                            method.getAnnotationOfType(Transactional.class).propagation(),
                            method.getFullName() + " must not create an adapter-owned transaction");
                    guardedBoundaries++;
                }
            }
        }
        assertTrue(guardedBoundaries > 0, "Persistence must reject calls without an application-selected scope");
    }

    @Test
    void transactionTemplateMechanicsRemainInTheDedicatedAdapter() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("za.co.shapeitsolutions.portfolio.resilientpay");

        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(SpringTransactionBoundary.class.getName())
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(TransactionTemplate.class.getName())
                .check(classes);
    }
}
