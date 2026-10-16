package za.co.shapeitsolutions.portfolio.resilientpay.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * Prevents framework leakage into the core and coupling between opposing adapter directions.
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
}
