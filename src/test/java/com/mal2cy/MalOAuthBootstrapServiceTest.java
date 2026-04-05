package com.mal2cy;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mal2cy.service.AuthTokenStore;
import com.mal2cy.service.MalOAuthBootstrapService;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class MalOAuthBootstrapServiceTest {

    @Test
    void buildAuthorizationUrlIncludesRequiredOauthParameters() {
        MalOAuthBootstrapService service = new MalOAuthBootstrapService(
                mock(OkHttpClient.class),
                mock(AuthTokenStore.class),
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "authUrl", "https://myanimelist.net/v1/oauth2/authorize");
        ReflectionTestUtils.setField(service, "clientId", "client-id");
        ReflectionTestUtils.setField(service, "clientSecret", "client-secret");
        ReflectionTestUtils.setField(service, "redirectUri", "http://localhost:8080/auth/mal/callback");

        String url = service.buildAuthorizationUrl();
        Map<String, String> params = parseQueryParams(url);

        assertTrue(url.startsWith("https://myanimelist.net/v1/oauth2/authorize"));
        assertTrue(params.containsKey("state"));
        assertTrue(params.containsKey("code_challenge"));
        assertTrue("code".equals(params.get("response_type")));
        assertTrue("client-id".equals(params.get("client_id")));
    }

    @Test
    void completeAuthorizationExchangesCodeAndPersistsTokens() throws Exception {
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        AuthTokenStore tokenStore = mock(AuthTokenStore.class);
        when(client.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(successResponse(
                "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\"}"));

        MalOAuthBootstrapService service = new MalOAuthBootstrapService(client, tokenStore, new ObjectMapper());
        ReflectionTestUtils.setField(service, "authUrl", "https://myanimelist.net/v1/oauth2/authorize");
        ReflectionTestUtils.setField(service, "tokenUrl", "https://myanimelist.net/v1/oauth2/token");
        ReflectionTestUtils.setField(service, "clientId", "client-id");
        ReflectionTestUtils.setField(service, "clientSecret", "client-secret");
        ReflectionTestUtils.setField(service, "redirectUri", "http://localhost:8080/auth/mal/callback");

        String url = service.buildAuthorizationUrl();
        String state = parseQueryParams(url).get("state");

        service.completeAuthorization("auth-code", state);

        verify(tokenStore).saveMalTokens("access-1", "refresh-1");
    }

    @Test
    void completeAuthorizationRejectsUnknownState() {
        MalOAuthBootstrapService service = new MalOAuthBootstrapService(
                mock(OkHttpClient.class),
                mock(AuthTokenStore.class),
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "clientId", "client-id");
        ReflectionTestUtils.setField(service, "clientSecret", "client-secret");

        assertThrows(IOException.class, () -> service.completeAuthorization("auth-code", "missing-state"));
    }

    private static Map<String, String> parseQueryParams(String url) {
        return Arrays.stream(URI.create(url).getQuery().split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(parts -> parts[0], parts -> parts.length > 1 ? parts[1] : ""));
    }

    private static Response successResponse(String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://myanimelist.net/v1/oauth2/token").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, okhttp3.MediaType.parse("application/json")))
                .build();
    }
}
