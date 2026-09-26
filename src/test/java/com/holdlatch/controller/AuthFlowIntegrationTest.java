package com.holdlatch.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.config.SecurityProperties;
import com.holdlatch.model.domain.UserRole;
import com.holdlatch.model.persistence.UserRecord;
import com.holdlatch.security.JwtService;
import com.holdlatch.support.AbstractPostgresTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTest extends AbstractPostgresTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SecurityProperties props;

    private MockHttpServletRequestBuilder jsonPost(String url, Object body, String remoteAddr) throws Exception {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)).with(r -> {
            r.setRemoteAddr(remoteAddr);
            return r;
        });
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private Map<String, String> registration(String email, String role) {
        return Map.of("email", email, "password", "correct-horse", "displayName", "Test User", "role", role);
    }

    private String loginToken(String email, String password, String ip) throws Exception {
        MvcResult result = mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", password), ip))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void registerLoginAndReadOwnProfile() throws Exception {
        String email = uniqueEmail();
        mvc.perform(jsonPost("/api/auth/register", registration(email, "CUSTOMER"), "10.0.0.1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email", is(email)))
                .andExpect(jsonPath("$.role", is("CUSTOMER")))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        String token = loginToken(email, "correct-horse", "10.0.0.1");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token).with(r -> {
                    r.setRemoteAddr("10.0.0.1");
                    return r;
                }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is(email)));
    }

    @Test
    void duplicateEmailIsRejectedIgnoringCase() throws Exception {
        String email = uniqueEmail();
        mvc.perform(jsonPost("/api/auth/register", registration(email, "CUSTOMER"), "10.0.0.2")).andExpect(status().isCreated());
        mvc.perform(jsonPost("/api/auth/register", registration(email.toUpperCase(), "ORGANIZER"), "10.0.0.2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("EMAIL_ALREADY_REGISTERED")));
    }

    @Test
    void wrongPasswordAndUnknownEmailLookIdentical() throws Exception {
        String email = uniqueEmail();
        mvc.perform(jsonPost("/api/auth/register", registration(email, "CUSTOMER"), "10.0.0.3")).andExpect(status().isCreated());

        MvcResult wrongPassword = mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", "nope-nope-nope"), "10.0.0.3"))
                .andExpect(status().isUnauthorized()).andReturn();
        MvcResult unknownEmail = mvc.perform(jsonPost("/api/auth/login", Map.of("email", uniqueEmail(), "password", "nope-nope-nope"), "10.0.0.3"))
                .andExpect(status().isUnauthorized()).andReturn();

        JsonNode a = json.readTree(wrongPassword.getResponse().getContentAsString());
        JsonNode b = json.readTree(unknownEmail.getResponse().getContentAsString());
        assertEquals(a.get("code"), b.get("code"));
        assertEquals(a.get("detail"), b.get("detail"));
    }

    @Test
    void adminCannotBeSelfRegisteredAndBadInputIsRejectedWithFieldErrors() throws Exception {
        mvc.perform(jsonPost("/api/auth/register", registration(uniqueEmail(), "ADMIN"), "10.0.0.4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ROLE_NOT_ALLOWED")));

        mvc.perform(jsonPost("/api/auth/register", Map.of("email", "not-an-email", "password", "short", "displayName", "", "role", "CUSTOMER"), "10.0.0.4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fields.email").exists())
                .andExpect(jsonPath("$.fields.password").exists())
                .andExpect(jsonPath("$.fields.displayName").exists());
    }

    @Test
    void protectedEndpointsRejectMissingTamperedAndExpiredTokens() throws Exception {
        String email = uniqueEmail();
        mvc.perform(jsonPost("/api/auth/register", registration(email, "CUSTOMER"), "10.0.0.5")).andExpect(status().isCreated());
        String token = loginToken(email, "correct-horse", "10.0.0.5");

        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")))
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")));

        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered)).andExpect(status().isUnauthorized());

        UserRecord ghost = new UserRecord(uniqueEmail(), "x", "Ghost", UserRole.CUSTOMER, Instant.now());
        Clock longAgo = Clock.fixed(Instant.now().minus(Duration.ofDays(2)), ZoneOffset.UTC);
        String expired = new JwtService(props, longAgo).issueFor(ghost).value();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());

        String foreignSecret = "a-completely-different-secret-of-sufficient-length";
        SecurityProperties other = new SecurityProperties(foreignSecret, props.issuer(), Duration.ofHours(1), 4, props.rateLimit());
        String forged = new JwtService(other, Clock.systemUTC()).issueFor(ghost).value();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + forged)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenNeverContainsThePasswordOrItsHash() throws Exception {
        String email = uniqueEmail();
        mvc.perform(jsonPost("/api/auth/register", registration(email, "CUSTOMER"), "10.0.0.6")).andExpect(status().isCreated());
        String token = loginToken(email, "correct-horse", "10.0.0.6");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        org.hamcrest.MatcherAssert.assertThat(payload, not(containsString("correct-horse")));
        org.hamcrest.MatcherAssert.assertThat(payload, not(containsString("$2a$")));
    }

    @Test
    void loginAttemptsFromOneAddressAreRateLimited() throws Exception {
        String ip = "10.9.9.9";
        int allowed = props.rateLimit().authCapacity();
        for (int i = 0; i < allowed; i++) {
            mvc.perform(jsonPost("/api/auth/login", Map.of("email", uniqueEmail(), "password", "whatever-123"), ip))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", uniqueEmail(), "password", "whatever-123"), ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code", is("RATE_LIMITED")));

        mvc.perform(jsonPost("/api/auth/login", Map.of("email", uniqueEmail(), "password", "whatever-123"), "10.9.9.10"))
                .andExpect(status().isUnauthorized());
    }
}
