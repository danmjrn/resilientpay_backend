package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

/**
 * Returns the single local-demo access token property expected by the browser client.
 *
 * @param accessToken short-lived token signed by the local demonstration issuer
 * @author Daniel Jr Nkulu
 */
public record DemoTokenResponse(String accessToken) {}
