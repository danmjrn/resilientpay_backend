package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

/**
 * Defines bounded local-demo personas and their least-privilege OAuth2 scopes.
 *
 * @author Daniel Jr Nkulu
 */
public enum DemoRole {
    REVERSAL_OPERATOR(
            "reversals.write reversals.read reversals.retry transactions.write transactions.read policies.read reconciliation.read audit.read",
            "urn:resilientpay:aal1"),
    RECONCILIATION_MAKER(
            "reversals.read reconciliation.read reconciliation.decide audit.read", "urn:resilientpay:aal1"),
    RECONCILIATION_CHECKER(
            "reversals.read reconciliation.read reconciliation.approve audit.read", "urn:resilientpay:aal2"),
    AUDITOR("reversals.read transactions.read policies.read reconciliation.read audit.read", "urn:resilientpay:aal1");

    private final String scopes;
    private final String assuranceLevel;

    DemoRole(String scopes, String assuranceLevel) {
        this.scopes = scopes;
        this.assuranceLevel = assuranceLevel;
    }

    /** Returns the space-delimited OAuth2 scope claim. */
    public String scopes() {
        return scopes;
    }

    /** Returns the bounded synthetic OIDC authentication-context claim. */
    public String assuranceLevel() {
        return assuranceLevel;
    }
}
