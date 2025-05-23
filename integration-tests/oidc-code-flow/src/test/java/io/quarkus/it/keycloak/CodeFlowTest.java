package io.quarkus.it.keycloak;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.util.Cookie;

import io.quarkus.oidc.runtime.OidcUtils;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.smallrye.jwt.util.KeyUtils;
import io.vertx.core.json.JsonObject;

/**
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
@QuarkusTest
@QuarkusTestResource(KeycloakRealmResourceManager.class)
public class CodeFlowTest {

    @Test
    public void testCodeFlowNoConsent() throws IOException {
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(false);
            WebResponse webResponse = webClient
                    .loadWebResponse(new WebRequest(URI.create("http://localhost:8081/index.html").toURL()));
            verifyLocationHeader(webClient, webResponse.getResponseHeaderValue("location"), null, "web-app", false);

            webClient.getCookieManager().clearCookies();

            webClient.getOptions().setRedirectEnabled(true);
            HtmlPage page = webClient.getPage("http://localhost:8081/index.html");

            assertEquals("/index.html", getStateCookieSavedPath(webClient, null));

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();

            assertEquals("Welcome to Test App", page.getTitleText());

            page = webClient.getPage("http://localhost:8081/index.html");

            assertEquals("Welcome to Test App", page.getTitleText(),
                    "A second request should not redirect and just re-authenticate the user");

            page = webClient.getPage("http://localhost:8081/web-app/configMetadataIssuer");

            assertEquals(
                    KeycloakRealmResourceManager.KEYCLOAK_SERVER_URL + "/realms/" + KeycloakRealmResourceManager.KEYCLOAK_REALM,
                    page.asText());

            page = webClient.getPage("http://localhost:8081/web-app/configMetadataScopes");

            assertTrue(page.asText().contains("openid"));
            assertTrue(page.asText().contains("profile"));

            Cookie sessionCookie = getSessionCookie(webClient, null);
            assertNotNull(sessionCookie);

            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testCodeFlowScopeError() throws IOException {
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(false);
            WebResponse webResponse = webClient
                    .loadWebResponse(
                            new WebRequest(URI.create("http://localhost:8081/index.html").toURL()));
            String keycloakUrl = webResponse.getResponseHeaderValue("location");

            // replace scope
            keycloakUrl = keycloakUrl.replace("scope=openid+profile+email+phone", "scope=unknown");

            // response from keycloak
            webResponse = webClient.loadWebResponse(new WebRequest(URI.create(keycloakUrl).toURL()));

            String endpointLocation = webResponse.getResponseHeaderValue("location");
            URI endpointLocationUri = URI.create(endpointLocation);

            // response from Quarkus
            webResponse = webClient.loadWebResponse(new WebRequest(endpointLocationUri.toURL()));
            assertEquals(401, webResponse.getStatusCode());
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testCodeFlowScopeErrorWithErrorPage() throws IOException {
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(false);

            WebResponse webResponse = webClient
                    .loadWebResponse(
                            new WebRequest(URI.create("http://localhost:8081/tenant-https/query?code=b").toURL()));
            String keycloakUrl = webResponse.getResponseHeaderValue("location");

            // replace scope
            keycloakUrl = keycloakUrl.replace("scope=openid+profile+email+phone", "scope=unknown");

            // response from keycloak
            webResponse = webClient.loadWebResponse(new WebRequest(URI.create(keycloakUrl).toURL()));

            // This is a redirect from the OIDC server to the endpoint
            String endpointLocation = webResponse.getResponseHeaderValue("location");
            assertTrue(endpointLocation.startsWith("https"));
            endpointLocation = "http" + endpointLocation.substring(5);
            URI endpointLocationUri = URI.create(endpointLocation);

            webResponse = webClient.loadWebResponse(new WebRequest(endpointLocationUri.toURL()));

            // This is a redirect from quarkus-oidc to the error page
            String endpointErrorLocation = webResponse.getResponseHeaderValue("location");
            assertTrue(endpointErrorLocation.startsWith("https"));

            endpointErrorLocation = "http" + endpointErrorLocation.substring(5);

            HtmlPage page = webClient.getPage(URI.create(endpointErrorLocation).toURL());
            assertEquals("code: b, error: invalid_scope, error_description: Invalid scopes: unknown",
                    page.getBody().asText());
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testCodeFlowForceHttpsRedirectUriAndPkce() throws Exception {
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(false);
            // Verify X-Forwarded-Prefix header is checked during all the redirects
            webClient.addRequestHeader("X-Forwarded-Prefix", "/xforwarded");

            WebResponse webResponse = webClient
                    .loadWebResponse(
                            new WebRequest(URI.create("http://localhost:8081/tenant-https").toURL()));
            String keycloakUrl = webResponse.getResponseHeaderValue("location");
            verifyLocationHeader(webClient, keycloakUrl, "tenant-https_test", "xforwarded%2Ftenant-https",
                    true);

            HtmlPage page = webClient.getPage(keycloakUrl);

            assertEquals("Sign in to quarkus", page.getTitleText());
            HtmlForm loginForm = page.getForms().get(0);
            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
            webResponse = loginForm.getInputByName("login").click().getWebResponse();
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(true);

            // This is a redirect from the OIDC server to the endpoint
            String endpointLocation = webResponse.getResponseHeaderValue("location");

            Cookie stateCookie = getStateCookie(webClient, "tenant-https_test");
            verifyCodeVerifier(stateCookie, keycloakUrl);

            assertTrue(endpointLocation.startsWith("https"));
            endpointLocation = "http" + endpointLocation.substring(5);
            assertTrue(endpointLocation.contains("/xforwarded"));
            endpointLocation = endpointLocation.replace("/xforwarded", "");
            URI endpointLocationUri = URI.create(endpointLocation);
            assertNotNull(endpointLocationUri.getRawQuery());

            webClient.addRequestHeader("X-Forwarded-Prefix", "/xforwarded");
            webResponse = webClient.loadWebResponse(new WebRequest(endpointLocationUri.toURL()));
            assertNull(getStateCookie(webClient, "tenant-https_test"));

            // This is a redirect from quarkus-oidc which drops the query parameters
            String endpointLocationWithoutQuery = webResponse.getResponseHeaderValue("location");
            assertTrue(endpointLocationWithoutQuery.startsWith("https"));
            assertTrue(endpointLocationWithoutQuery.contains("/xforwarded"));

            endpointLocationWithoutQuery = "http" + endpointLocationWithoutQuery.substring(5);
            endpointLocationWithoutQuery = endpointLocationWithoutQuery.replace("/xforwarded", "");
            URI endpointLocationWithoutQueryUri = URI.create(endpointLocationWithoutQuery);
            assertNull(endpointLocationWithoutQueryUri.getRawQuery());

            page = webClient.getPage(endpointLocationWithoutQueryUri.toURL());
            assertEquals("tenant-https:reauthenticated", page.getBody().asText());
            Cookie sessionCookie = getSessionCookie(webClient, "tenant-https_test");
            assertNotNull(sessionCookie);
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testCodeFlowForceHttpsRedirectUriWithQueryAndPkce() throws Exception {
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(false);

            WebResponse webResponse = webClient
                    .loadWebResponse(
                            new WebRequest(
                                    URI.create("http://localhost:8081/tenant-https/query?code=b&kc_idp_hint=google").toURL()));
            String keycloakUrl = webResponse.getResponseHeaderValue("location");
            String keycloakUrlQuery = URI.create(keycloakUrl).getQuery();
            assertTrue(keycloakUrlQuery.contains("kc_idp_hint=google"));
            assertFalse(keycloakUrlQuery.contains("code=b"));
            verifyLocationHeader(webClient, keycloakUrl, "tenant-https_test", "tenant-https",
                    true);

            HtmlPage page = webClient.getPage(keycloakUrl);

            assertEquals("Sign in to quarkus", page.getTitleText());
            HtmlForm loginForm = page.getForms().get(0);
            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
            webResponse = loginForm.getInputByName("login").click().getWebResponse();
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(true);

            // This is a redirect from the OIDC server to the endpoint
            String endpointLocation = webResponse.getResponseHeaderValue("location");
            assertTrue(endpointLocation.startsWith("https"));
            endpointLocation = "http" + endpointLocation.substring(5);
            URI endpointLocationUri = URI.create(endpointLocation);
            assertNotNull(endpointLocationUri.getRawQuery());

            Cookie stateCookie = getStateCookie(webClient, "tenant-https_test");
            verifyCodeVerifier(stateCookie, keycloakUrl);

            webResponse = webClient.loadWebResponse(new WebRequest(endpointLocationUri.toURL()));
            assertNull(getStateCookie(webClient, "tenant-https_test"));

            // This is a redirect from quarkus-oidc which drops the code and state query parameters
            String endpointLocationWithoutQuery = webResponse.getResponseHeaderValue("location");
            assertTrue(endpointLocationWithoutQuery.startsWith("https"));

            endpointLocationWithoutQuery = "http" + endpointLocationWithoutQuery.substring(5);
            URI endpointLocationWithoutQueryUri = URI.create(endpointLocationWithoutQuery);
            assertEquals("code=b", endpointLocationWithoutQueryUri.getRawQuery());

            Cookie sessionCookie = getSessionCookie(webClient, "tenant-https_test");
            assertNotNull(sessionCookie);
            JsonObject idToken = OidcUtils.decodeJwtContent(sessionCookie.getValue().split("\\|")[0]);
            String expiresAt = idToken.getInteger("exp").toString();
            page = webClient.getPage(endpointLocationWithoutQueryUri.toURL());
            String response = page.getBody().asText();
            assertTrue(
                    response.startsWith("tenant-https:reauthenticated?code=b&expiresAt=" + expiresAt + "&expiresInDuration="));
            Integer duration = Integer.valueOf(response.substring(response.length() - 1));
            assertTrue(duration > 1 && duration < 5);
            sessionCookie = getSessionCookie(webClient, "tenant-https_test");
            assertNotNull(sessionCookie);
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testCodeFlowForceHttpsRedirectUriAndPkceMissingCodeVerifier() throws Exception {
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(false);
            // Verify X-Forwarded-Prefix header is checked during all the redirects
            webClient.addRequestHeader("X-Forwarded-Prefix", "/xforwarded");

            WebResponse webResponse = webClient
                    .loadWebResponse(
                            new WebRequest(URI.create("http://localhost:8081/tenant-https").toURL()));
            String keycloakUrl = webResponse.getResponseHeaderValue("location");
            verifyLocationHeader(webClient, keycloakUrl, "tenant-https_test", "xforwarded%2Ftenant-https",
                    true);

            HtmlPage page = webClient.getPage(keycloakUrl);

            assertEquals("Sign in to quarkus", page.getTitleText());
            HtmlForm loginForm = page.getForms().get(0);
            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
            webResponse = loginForm.getInputByName("login").click().getWebResponse();
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(true);

            // This is a redirect from the OIDC server to the endpoint
            String endpointLocation = webResponse.getResponseHeaderValue("location");

            Cookie stateCookie = getStateCookie(webClient, "tenant-https_test");
            verifyCodeVerifier(stateCookie, keycloakUrl);

            assertTrue(endpointLocation.startsWith("https"));
            endpointLocation = "http" + endpointLocation.substring(5);
            assertTrue(endpointLocation.contains("/xforwarded"));
            endpointLocation = endpointLocation.replace("/xforwarded", "");
            URI endpointLocationUri = URI.create(endpointLocation);
            assertNotNull(endpointLocationUri.getRawQuery());
            webClient.addRequestHeader("X-Forwarded-Prefix", "/xforwarded");

            String cookieValueWithoutCodeVerifier = stateCookie.getValue().split("\\|")[0];
            Cookie stateCookieWithoutCodeVerifier = new Cookie(stateCookie.getDomain(), stateCookie.getName(),
                    cookieValueWithoutCodeVerifier);
            webClient.getCookieManager().clearCookies();
            webClient.getCookieManager().addCookie(stateCookieWithoutCodeVerifier);
            Cookie stateCookie2 = getStateCookie(webClient, "tenant-https_test");
            assertEquals(cookieValueWithoutCodeVerifier, stateCookie2.getValue());

            webResponse = webClient.loadWebResponse(new WebRequest(endpointLocationUri.toURL()));
            assertEquals(401, webResponse.getStatusCode());
            assertNull(getStateCookie(webClient, "tenant-https_test"));

            webClient.getCookieManager().clearCookies();
        }
    }

    private void verifyCodeVerifier(Cookie stateCookie, String keycloakUrl) throws Exception {
        String encodedState = stateCookie.getValue().split("\\|")[1];

        String codeVerifier = OidcUtils.decryptJson(encodedState,
                KeyUtils.createSecretKeyFromSecret("eUk1p7UB3nFiXZGUXi0uph1Y9p34YhBU")).getString("code_verifier");
        String codeChallenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(OidcUtils.getSha256Digest(codeVerifier.getBytes(StandardCharsets.US_ASCII)));

        assertTrue(keycloakUrl.contains("code_challenge=" + codeChallenge));
    }

    private void verifyLocationHeader(WebClient webClient, String loc, String tenant, String path, boolean httpsScheme) {
        assertTrue(loc.startsWith("http://localhost:8180/auth/realms/quarkus/protocol/openid-connect/auth"));
        String scheme = httpsScheme ? "https" : "http";
        assertTrue(loc.contains("redirect_uri=" + scheme + "%3A%2F%2Flocalhost%3A8081%2F" + path));
        assertTrue(loc.contains("state=" + getStateCookieStateParam(webClient, tenant)));
        assertTrue(loc.contains("scope=openid+profile+email+phone"));
        assertTrue(loc.contains("response_type=code"));
        assertTrue(loc.contains("client_id=quarkus-app"));
        assertTrue(loc.contains("max-age=60"));

        if (httpsScheme) {
            assertTrue(loc.contains("code_challenge"));
            assertTrue(loc.contains("code_challenge_method=S256"));
        }
    }

    @Test
    public void testTokenTimeoutLogout() throws IOException, InterruptedException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/index.html");
            assertEquals("/index.html", getStateCookieSavedPath(webClient, null));

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();

            assertEquals("Welcome to Test App", page.getTitleText());
            assertNull(getStateCookie(webClient, null));
            assertNull(getSessionAtCookie(webClient, null));
            assertNull(getSessionRtCookie(webClient, null));
            Cookie sessionCookie = getSessionCookie(webClient, null);
            assertNotNull(sessionCookie);
            assertEquals("/", sessionCookie.getPath());
            assertEquals("localhost", sessionCookie.getDomain());

            webClient.getOptions().setRedirectEnabled(false);
            webClient.getCache().clear();

            await().atLeast(6, TimeUnit.SECONDS)
                    .pollDelay(Duration.ofSeconds(6))
                    .until(new Callable<Boolean>() {
                        @Override
                        public Boolean call() throws Exception {
                            WebResponse webResponse = webClient
                                    .loadWebResponse(new WebRequest(URI.create("http://localhost:8081/index.html").toURL()));
                            assertEquals(302, webResponse.getStatusCode());
                            assertNull(getSessionCookie(webClient, null));
                            return true;
                        }
                    });

            webClient.getOptions().setRedirectEnabled(true);
            webClient.getCookieManager().clearCookies();
            page = webClient.getPage("http://localhost:8081/index.html");

            assertEquals("Sign in to quarkus", page.getTitleText());
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testRPInitiatedLogout() throws IOException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/tenant-logout");
            assertEquals("Sign in to logout-realm", page.getTitleText());
            HtmlForm loginForm = page.getForms().get(0);
            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");
            page = loginForm.getInputByName("login").click();
            assertEquals("Tenant Logout, refreshed: false", page.asText());
            assertNotNull(getSessionCookie(webClient, "tenant-logout"));

            page = webClient.getPage("http://localhost:8081/tenant-logout/logout");
            assertTrue(page.asText().contains("You were logged out"));
            assertNull(getSessionCookie(webClient, "tenant-logout"));

            page = webClient.getPage("http://localhost:8081/tenant-logout");
            assertEquals("Sign in to logout-realm", page.getTitleText());
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testTokenRefresh() throws IOException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/tenant-refresh");
            assertEquals("Sign in to logout-realm", page.getTitleText());
            HtmlForm loginForm = page.getForms().get(0);
            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");
            page = loginForm.getInputByName("login").click();
            assertEquals("Tenant Refresh, refreshed: false", page.asText());

            Cookie sessionCookie = getSessionCookie(webClient, "tenant-refresh");
            assertNotNull(sessionCookie);
            String idToken = getIdToken(sessionCookie);

            //wait now so that we reach the ID token timeout
            await().atMost(10, TimeUnit.SECONDS)
                    .pollInterval(Duration.ofSeconds(2))
                    .until(new Callable<Boolean>() {
                        @Override
                        public Boolean call() throws Exception {
                            webClient.getOptions().setRedirectEnabled(false);
                            WebResponse webResponse = webClient
                                    .loadWebResponse(
                                            new WebRequest(URI.create("http://localhost:8081/tenant-refresh").toURL()));
                            assertEquals(200, webResponse.getStatusCode());
                            // Should not redirect to OP but silently refresh token
                            Cookie newSessionCookie = getSessionCookie(webClient, "tenant-refresh");
                            assertNotNull(newSessionCookie);
                            return webResponse.getContentAsString().equals("Tenant Refresh, refreshed: true")
                                    && !idToken.equals(getIdToken(newSessionCookie));
                        }
                    });

            // local session refreshed and still valid
            page = webClient.getPage("http://localhost:8081/tenant-refresh");
            assertEquals("Tenant Refresh, refreshed: false", page.asText());
            assertNotNull(getSessionCookie(webClient, "tenant-refresh"));

            //wait now so that we reach the refresh timeout
            await().atMost(20, TimeUnit.SECONDS)
                    .pollInterval(Duration.ofSeconds(1))
                    .until(new Callable<Boolean>() {
                        @Override
                        public Boolean call() throws Exception {
                            webClient.getOptions().setRedirectEnabled(false);
                            WebResponse webResponse = webClient
                                    .loadWebResponse(
                                            new WebRequest(URI.create("http://localhost:8081/tenant-refresh").toURL()));
                            // Should redirect to login page given that session is now expired at the OP
                            int statusCode = webResponse.getStatusCode();

                            if (statusCode == 302) {
                                assertNull(getSessionCookie(webClient, "tenant-refresh"));
                                return true;
                            }

                            return false;
                        }
                    });

            // session invalidated because it ended at the OP, should redirect to login page at the OP
            webClient.getOptions().setRedirectEnabled(true);
            webClient.getCookieManager().clearCookies();

            page = webClient.getPage("http://localhost:8081/tenant-refresh");
            assertNull(getSessionCookie(webClient, "tenant-logout"));
            assertEquals("Sign in to logout-realm", page.getTitleText());
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    // See https://github.com/quarkusio/quarkus/issues/27900
    @Disabled("flaky")
    public void testTokenAutoRefresh() throws IOException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/tenant-autorefresh");
            assertEquals("Sign in to quarkus", page.getTitleText());
            HtmlForm loginForm = page.getForms().get(0);
            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");
            page = loginForm.getInputByName("login").click();
            assertEquals("Tenant AutoRefresh, refreshed: false", page.asText());

            Cookie sessionCookie = getSessionCookie(webClient, "tenant-autorefresh");
            assertNotNull(sessionCookie);
            String idToken = getIdToken(sessionCookie);

            // Auto-refresh-interval is 30 secs so every call auto-refreshes the token
            // Right now the original ID token is still valid but will be auto-refreshed
            page = webClient.getPage("http://localhost:8081/tenant-autorefresh");
            assertEquals("Tenant AutoRefresh, refreshed: true", page.asText());
            sessionCookie = getSessionCookie(webClient, "tenant-autorefresh");
            assertNotNull(sessionCookie);
            String nextIdToken = getIdToken(sessionCookie);
            assertNotEquals(idToken, nextIdToken);

            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testIdTokenInjection() throws IOException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/index.html");
            assertEquals("/index.html", getStateCookieSavedPath(webClient, null));

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();

            assertEquals("Welcome to Test App", page.getTitleText());

            page = webClient.getPage("http://localhost:8081/web-app");

            assertEquals("alice", page.getBody().asText());
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testIdTokenInjectionWithoutRestoredPath() throws IOException, InterruptedException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/web-app/callback-before-redirect");
            assertNotNull(getStateCookieStateParam(webClient, "tenant-1"));
            assertNull(getStateCookieSavedPath(webClient, "tenant-1"));

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();

            assertEquals("callback:alice", page.getBody().asText());
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testIdTokenInjectionJwtMethod() throws IOException, InterruptedException {
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(false);
            WebResponse webResponse = webClient
                    .loadWebResponse(
                            new WebRequest(URI.create("http://localhost:8081/web-app/callback-jwt-before-redirect").toURL()));
            assertNotNull(getStateCookie(webClient, "tenant-jwt"));
            assertNotNull(getStateCookieStateParam(webClient, "tenant-jwt"));
            assertNull(getStateCookieSavedPath(webClient, "tenant-jwt"));

            HtmlPage page = webClient.getPage(webResponse.getResponseHeaderValue("location"));
            assertEquals("Sign in to quarkus", page.getTitleText());
            HtmlForm loginForm = page.getForms().get(0);
            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
            webResponse = loginForm.getInputByName("login").click().getWebResponse();
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(true);

            // This is a redirect from the OIDC server to the endpoint
            URI endpointLocationUri = URI.create(webResponse.getResponseHeaderValue("location"));
            assertNotNull(endpointLocationUri.getRawQuery());
            webResponse = webClient.loadWebResponse(new WebRequest(endpointLocationUri.toURL()));

            // This is a redirect from quarkus-oidc which drops the query parameters
            URI endpointLocationUri2 = URI.create(webResponse.getResponseHeaderValue("location"));
            assertNull(endpointLocationUri2.getRawQuery());

            page = webClient.getPage(endpointLocationUri2.toString());
            assertEquals("callback-jwt:alice", page.getBody().asText());
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testIdTokenInjectionJwtMethodButPostMethodUsed() throws IOException, InterruptedException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/web-app/callback-jwt-not-used-before-redirect");
            assertNotNull(getStateCookieStateParam(webClient, "tenant-jwt-not-used"));
            assertNull(getStateCookieSavedPath(webClient, "tenant-jwt-not-used"));

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            try {
                loginForm.getInputByName("login").click();
                fail("401 status error is expected");
            } catch (FailingHttpStatusCodeException ex) {
                assertEquals(401, ex.getStatusCode());
            }

            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testIdTokenInjectionWithoutRestoredPathDifferentRoot() throws IOException, InterruptedException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/web-app2/callback-before-redirect?tenantId=tenant-2");
            assertNotNull(getStateCookieStateParam(webClient, "tenant-2"));
            assertEquals("?tenantId=tenant-2", getStateCookieSavedPath(webClient, "tenant-2"));

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();

            assertEquals("web-app2:alice", page.getBody().asText());

            page = webClient.getPage("http://localhost:8081/web-app2/name");

            assertEquals("web-app2:alice", page.getBody().asText());

            assertNull(getStateCookie(webClient, "tenant-2"));
            Cookie sessionCookie = getSessionCookie(webClient, "tenant-2");
            assertNotNull(sessionCookie);
            assertEquals("/web-app2", sessionCookie.getPath());

            Thread.sleep(5000);
            webClient.getOptions().setRedirectEnabled(false);
            WebResponse webResponse = webClient
                    .loadWebResponse(new WebRequest(URI.create("http://localhost:8081/web-app2/name").toURL()));
            assertEquals(302, webResponse.getStatusCode());
            assertNull(getSessionCookie(webClient, "tenant-2"));

            webClient.getOptions().setRedirectEnabled(true);
            webClient.getCookieManager().clearCookies();

            page = webClient.getPage("http://localhost:8081/web-app2/name");

            assertEquals("Sign in to quarkus", page.getTitleText());

            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testAuthenticationCompletionFailedNoStateCookie() throws IOException, InterruptedException {
        // tenant-3 configuration uses a '/web-app3' redirect parameter which does not have the same root
        // as the original request which is 'web-app', as a result, when the user is returned back to Quarkus
        // to '/web-app3' no state cookie is detected.
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/web-app/callback-before-redirect?tenantId=tenant-3");
            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");
            try {
                loginForm.getInputByName("login").click();
                fail("401 status error is expected");
            } catch (FailingHttpStatusCodeException ex) {
                assertEquals(401, ex.getStatusCode());
            }
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testAuthenticationCompletionFailedWrongRedirectUri() throws IOException, InterruptedException {
        // CustomTenantResolver will return null for an empty tenantId which will lead to the default configuration
        // being used and result in '/web-app/callback-before-redirect' be used as an initial redirect_uri parameter.
        // When the user is redirected back, CustomTenantResolver will resolve a 'tenant-1' configuration with
        // a redirect_uri '/web-app/callback-after-redirect' which will cause a code to token exchange failure
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/web-app/callback-before-wrong-redirect");
            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");
            try {
                page = loginForm.getInputByName("login").click();
                fail("401 status error is expected: " + page.getBody().asText());
            } catch (FailingHttpStatusCodeException ex) {
                assertEquals(401, ex.getStatusCode());
                assertEquals("http://localhost:8081/web-app/callback-before-wrong-redirect",
                        ex.getResponse().getResponseHeaderValue("RedirectUri"));
            }
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testAccessTokenInjection() throws IOException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/index.html");
            assertEquals("/index.html", getStateCookieSavedPath(webClient, null));

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();

            assertEquals("Welcome to Test App", page.getTitleText());

            page = webClient.getPage("http://localhost:8081/web-app/access");

            assertEquals("AT injected", page.getBody().asText());
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testAccessAndRefreshTokenInjection() throws IOException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/index.html");
            assertEquals("/index.html", getStateCookieSavedPath(webClient, null));

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();

            assertEquals("Welcome to Test App", page.getTitleText());

            page = webClient.getPage("http://localhost:8081/web-app/refresh");

            assertEquals("RT injected", page.getBody().asText());
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testAccessAndRefreshTokenInjectionWithoutIndexHtml() throws IOException, InterruptedException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/web-app/refresh");
            assertEquals("/web-app/refresh", getStateCookieSavedPath(webClient, null));

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();

            assertEquals("RT injected", page.getBody().asText());
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testDefaultSessionManagerIdTokenOnly() throws IOException, InterruptedException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/web-app/tenant-idtoken-only");
            assertNotNull(getStateCookie(webClient, "tenant-idtoken-only"));

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();
            assertEquals("tenant-idtoken-only:alice", page.getBody().asText());

            page = webClient.getPage("http://localhost:8081/web-app/access/tenant-idtoken-only");
            assertEquals("tenant-idtoken-only:no access", page.getBody().asText());
            page = webClient.getPage("http://localhost:8081/web-app/refresh/tenant-idtoken-only");
            assertEquals("tenant-idtoken-only:no refresh", page.getBody().asText());

            Cookie idTokenCookie = getSessionCookie(page.getWebClient(), "tenant-idtoken-only");
            checkSingleTokenCookie(idTokenCookie, "ID");

            assertNull(getSessionAtCookie(webClient, "tenant-idtoken-only"));
            assertNull(getSessionRtCookie(webClient, "tenant-idtoken-only"));

            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testDefaultSessionManagerIdRefreshTokens() throws IOException, InterruptedException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/web-app/tenant-id-refresh-token");
            assertNotNull(getStateCookie(webClient, "tenant-id-refresh-token"));

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();
            assertEquals("tenant-id-refresh-token:alice", page.getBody().asText());

            page = webClient.getPage("http://localhost:8081/web-app/access/tenant-id-refresh-token");
            assertEquals("tenant-id-refresh-token:no access", page.getBody().asText());
            page = webClient.getPage("http://localhost:8081/web-app/refresh/tenant-id-refresh-token");
            assertEquals("tenant-id-refresh-token:RT injected", page.getBody().asText());

            Cookie idTokenCookie = getSessionCookie(page.getWebClient(), "tenant-id-refresh-token");
            String[] parts = idTokenCookie.getValue().split("\\|");
            assertEquals(3, parts.length);
            assertEquals("ID", OidcUtils.decodeJwtContent(parts[0]).getString("typ"));
            assertEquals("", parts[1]);
            assertEquals("Refresh", OidcUtils.decodeJwtContent(parts[2]).getString("typ"));

            assertNull(getSessionAtCookie(webClient, "tenant-id-refresh-token"));
            assertNull(getSessionRtCookie(webClient, "tenant-id-refresh-token"));

            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testDefaultSessionManagerSplitTokens() throws IOException, InterruptedException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/web-app/tenant-split-tokens");
            assertNotNull(getStateCookie(webClient, "tenant-split-tokens"));

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();
            assertEquals("tenant-split-tokens:alice, id token has 5 parts, access token has 5 parts, refresh token has 5 parts",
                    page.getBody().asText());

            page = webClient.getPage("http://localhost:8081/web-app/access/tenant-split-tokens");
            assertEquals("tenant-split-tokens:AT injected", page.getBody().asText());
            page = webClient.getPage("http://localhost:8081/web-app/refresh/tenant-split-tokens");
            assertEquals("tenant-split-tokens:RT injected", page.getBody().asText());

            Cookie idTokenCookie = getSessionCookie(page.getWebClient(), "tenant-split-tokens");
            checkSingleTokenCookie(idTokenCookie, "ID", true);

            Cookie atTokenCookie = getSessionAtCookie(page.getWebClient(), "tenant-split-tokens");
            checkSingleTokenCookie(atTokenCookie, "Bearer", true);

            Cookie rtTokenCookie = getSessionRtCookie(page.getWebClient(), "tenant-split-tokens");
            checkSingleTokenCookie(rtTokenCookie, "Refresh", true);

            // verify all the cookies are cleared after the session timeout
            webClient.getOptions().setRedirectEnabled(false);
            webClient.getCache().clear();

            await().atLeast(6, TimeUnit.SECONDS)
                    .pollDelay(Duration.ofSeconds(6))
                    .until(new Callable<Boolean>() {
                        @Override
                        public Boolean call() throws Exception {
                            WebResponse webResponse = webClient
                                    .loadWebResponse(new WebRequest(URI.create("http://localhost:8081/index.html").toURL()));
                            assertEquals(302, webResponse.getStatusCode());
                            assertNull(getSessionCookie(webClient, null));
                            return true;
                        }
                    });

            assertNull(getSessionCookie(page.getWebClient(), "tenant-split-tokens"));
            assertNull(getSessionAtCookie(page.getWebClient(), "tenant-split-tokens"));
            assertNull(getSessionRtCookie(page.getWebClient(), "tenant-split-tokens"));

            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testDefaultSessionManagerIdRefreshSplitTokens() throws IOException, InterruptedException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/web-app/tenant-split-id-refresh-token");
            assertNotNull(getStateCookie(webClient, "tenant-split-id-refresh-token"));

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();
            assertEquals("tenant-split-id-refresh-token:alice", page.getBody().asText());

            page = webClient.getPage("http://localhost:8081/web-app/access/tenant-split-id-refresh-token");
            assertEquals("tenant-split-id-refresh-token:no access", page.getBody().asText());
            page = webClient.getPage("http://localhost:8081/web-app/refresh/tenant-split-id-refresh-token");
            assertEquals("tenant-split-id-refresh-token:RT injected", page.getBody().asText());

            Cookie idTokenCookie = getSessionCookie(page.getWebClient(), "tenant-split-id-refresh-token");
            checkSingleTokenCookie(idTokenCookie, "ID");

            assertNull(getSessionAtCookie(page.getWebClient(), "tenant-split-id-refresh-token"));

            Cookie rtTokenCookie = getSessionRtCookie(page.getWebClient(), "tenant-split-id-refresh-token");
            checkSingleTokenCookie(rtTokenCookie, "Refresh");

            // verify all the cookies are cleared after the session timeout
            webClient.getOptions().setRedirectEnabled(false);
            webClient.getCache().clear();

            await().atLeast(6, TimeUnit.SECONDS)
                    .pollDelay(Duration.ofSeconds(6))
                    .until(new Callable<Boolean>() {
                        @Override
                        public Boolean call() throws Exception {
                            WebResponse webResponse = webClient
                                    .loadWebResponse(new WebRequest(URI.create("http://localhost:8081/index.html").toURL()));
                            assertEquals(302, webResponse.getStatusCode());
                            assertNull(getSessionCookie(webClient, null));
                            return true;
                        }
                    });

            assertNull(getSessionCookie(page.getWebClient(), "tenant-split-id-refresh-token"));
            assertNull(getSessionAtCookie(page.getWebClient(), "tenant-split-id-refresh-token"));
            assertNull(getSessionRtCookie(page.getWebClient(), "tenant-split-id-refresh-token"));

            webClient.getCookieManager().clearCookies();
        }
    }

    private void checkSingleTokenCookie(Cookie tokenCookie, String type) {
        checkSingleTokenCookie(tokenCookie, type, false);

    }

    private void checkSingleTokenCookie(Cookie tokenCookie, String type, boolean decrypt) {
        String[] cookieParts = tokenCookie.getValue().split("\\|");
        assertEquals(1, cookieParts.length);
        String token = cookieParts[0];
        String[] tokenParts = token.split("\\.");
        if (decrypt) {
            assertEquals(5, tokenParts.length);
            try {
                token = OidcUtils.decryptString(token, KeyUtils.createSecretKeyFromSecret("eUk1p7UB3nFiXZGUXi0uph1Y9p34YhBU"));
                tokenParts = token.split("\\.");
            } catch (Exception ex) {
                fail("Token decryption has failed");
            }
        }
        assertEquals(3, tokenParts.length);
        assertEquals(type, OidcUtils.decodeJwtContent(token).getString("typ"));
    }

    @Test
    public void testAccessAndRefreshTokenInjectionWithoutIndexHtmlAndListener() throws IOException, InterruptedException {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/web-app/refresh/tenant-listener");

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();

            assertEquals("RT injected(event:OIDC_LOGIN,tenantId:tenant-listener,blockingApi:true)", page.getBody().asText());
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testAccessAndRefreshTokenInjectionWithQuery() throws Exception {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/web-app/refresh-query?a=aValue");
            assertEquals("/web-app/refresh-query?a=aValue", getStateCookieSavedPath(webClient, null));

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();

            assertEquals("RT injected:aValue", page.getBody().asText());
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testRedirectUriWithForwardedPrefix() throws IOException, InterruptedException {
        //doTestRedirectUriWithForwardedPrefix("/service");
        doTestRedirectUriWithForwardedPrefix("/service/");
        //doTestRedirectUriWithForwardedPrefix("");
        //doTestRedirectUriWithForwardedPrefix("/");
        //doTestRedirectUriWithForwardedPrefix("//");
    }

    private void doTestRedirectUriWithForwardedPrefix(String prefix) throws IOException, InterruptedException {
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(false);
            webClient.addRequestHeader("X-Forwarded-Prefix", prefix);
            WebResponse webResponse = webClient
                    .loadWebResponse(new WebRequest(URI.create("http://localhost:8081/index.html").toURL()));
            String loc = webResponse.getResponseHeaderValue("location");
            String encodedPrefix;
            if (prefix.isEmpty() || prefix.equals("/") || prefix.equals("//")) {
                encodedPrefix = "%2F";
            } else {
                encodedPrefix = prefix.replaceAll("\\/", "%2F");
            }
            assertTrue(loc.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8081" + encodedPrefix + "web-app"));
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testJavaScriptRequest() throws IOException, InterruptedException {
        try (final WebClient webClient = createWebClient()) {
            try {
                webClient.addRequestHeader("X-Requested-With", "JavaScript");
                webClient.getPage("http://localhost:8081/tenant-javascript");
                fail("499 status error is expected");
            } catch (FailingHttpStatusCodeException ex) {
                assertEquals(499, ex.getStatusCode());
                assertEquals("OIDC", ex.getResponse().getResponseHeaderValue("WWW-Authenticate"));
            }

            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testCookiePathHeader() throws IOException, InterruptedException {
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(false);
            webClient.addRequestHeader("X-Forwarded-Prefix", "/x-forwarded-prefix-value");
            WebResponse webResponse = webClient
                    .loadWebResponse(new WebRequest(URI.create("http://localhost:8081/tenant-cookie-path-header").toURL()));
            assertEquals(302, webResponse.getStatusCode());
            assertEquals("/x-forwarded-prefix-value", getStateCookie(webClient, "tenant-cookie-path-header").getPath());
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testNoCodeFlowUnprotected() {
        RestAssured.when().get("/public-web-app/name")
                .then()
                .statusCode(200)
                .body(Matchers.equalTo("no user"));
    }

    @Test
    public void testCustomLogin() throws Exception {
        try (final WebClient webClient = createWebClient()) {
            HtmlPage page = webClient.getPage("http://localhost:8081/public-web-app/login");

            assertEquals("Sign in to quarkus", page.getTitleText());

            HtmlForm loginForm = page.getForms().get(0);

            loginForm.getInputByName("username").setValueAttribute("alice");
            loginForm.getInputByName("password").setValueAttribute("alice");

            page = loginForm.getInputByName("login").click();

            assertEquals("alice", page.getBody().asText());
        }
    }

    private WebClient createWebClient() {
        WebClient webClient = new WebClient();
        webClient.setCssErrorHandler(new SilentCssErrorHandler());
        return webClient;
    }

    private Cookie getStateCookie(WebClient webClient, String tenantId) {
        return webClient.getCookieManager().getCookie("q_auth" + (tenantId == null ? "_Default_test" : "_" + tenantId));
    }

    private String getStateCookieStateParam(WebClient webClient, String tenantId) {
        return getStateCookie(webClient, tenantId).getValue().split("\\|")[0];
    }

    private String getStateCookieSavedPath(WebClient webClient, String tenantId) {
        String[] parts = getStateCookie(webClient, tenantId).getValue().split("\\|");
        return parts.length == 2 ? parts[1] : null;
    }

    private Cookie getSessionCookie(WebClient webClient, String tenantId) {
        return webClient.getCookieManager().getCookie("q_session" + (tenantId == null ? "_Default_test" : "_" + tenantId));
    }

    private Cookie getSessionAtCookie(WebClient webClient, String tenantId) {
        return webClient.getCookieManager().getCookie("q_session_at" + (tenantId == null ? "_Default_test" : "_" + tenantId));
    }

    private Cookie getSessionRtCookie(WebClient webClient, String tenantId) {
        return webClient.getCookieManager().getCookie("q_session_rt" + (tenantId == null ? "_Default_test" : "_" + tenantId));
    }

    private String getIdToken(Cookie sessionCookie) {
        return sessionCookie.getValue().split("\\|")[0];
    }
}
