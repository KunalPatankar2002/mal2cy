package com.mal2cy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.mal2cy.service.AuthTokenStore;
import com.mal2cy.service.MalService;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class MalServiceTest {

    @Test
    void getAnimeListParsesEntriesFromResponse() throws Exception {
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        AuthTokenStore authTokenStore = mock(AuthTokenStore.class);
        when(client.newCall(org.mockito.ArgumentMatchers.any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(successResponse(
                "{\"data\":[{\"node\":{\"id\":1,\"title\":\"Frieren\","
                        + "\"num_episodes\":28,"
                        + "\"alternative_titles\":{\"en\":\"Frieren: Beyond Journey's End\",\"synonyms\":[\"Sousou no Frieren\"]}},"
                        + "\"list_status\":{\"status\":\"watching\",\"num_episodes_watched\":12}}]}"));
        when(authTokenStore.loadTokens()).thenReturn(Map.of());

        MalService malService = new MalService();
        ReflectionTestUtils.setField(malService, "okHttpClient", client);
        ReflectionTestUtils.setField(malService, "authTokenStore", authTokenStore);
        ReflectionTestUtils.setField(malService, "baseUrl", "https://api.example.test");
        ReflectionTestUtils.setField(malService, "accessToken", "token");

        List<java.util.Map<String, Object>> animeList = malService.getAnimeList();

        assertEquals(1, animeList.size());
        assertEquals("1", animeList.get(0).get("malId"));
        assertEquals("Frieren", animeList.get(0).get("title"));
        assertEquals("watching", animeList.get(0).get("status"));
        assertEquals(12, animeList.get(0).get("watchedEpisodes"));
        assertEquals(28, animeList.get(0).get("totalEpisodes"));
        assertEquals(List.of("Frieren: Beyond Journey's End", "Frieren", "Sousou no Frieren"),
                animeList.get(0).get("titleCandidates"));
    }

    @Test
    void findMalIdByTitleReturnsNullWhenSearchIsUnsuccessful() throws Exception {
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        when(client.newCall(org.mockito.ArgumentMatchers.any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(errorResponse());

        MalService malService = new MalService();
        ReflectionTestUtils.setField(malService, "okHttpClient", client);

        assertNull(malService.findMalIdByTitle("Unknown"));
    }

    @Test
    void updateAnimeStatusThrowsWhenRemoteUpdateFails() throws Exception {
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        AuthTokenStore authTokenStore = mock(AuthTokenStore.class);
        when(client.newCall(org.mockito.ArgumentMatchers.any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(errorResponse());
        when(authTokenStore.loadTokens()).thenReturn(Map.of());

        MalService malService = new MalService();
        ReflectionTestUtils.setField(malService, "okHttpClient", client);
        ReflectionTestUtils.setField(malService, "authTokenStore", authTokenStore);
        ReflectionTestUtils.setField(malService, "baseUrl", "https://api.example.test");
        ReflectionTestUtils.setField(malService, "accessToken", "token");

        assertThrows(IOException.class, () -> malService.updateAnimeStatus("1", "watching"));
    }

    @Test
    void updateAnimeProgressSendsFormEncodedStatusAndEpisodeCount() throws Exception {
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        AuthTokenStore authTokenStore = mock(AuthTokenStore.class);
        when(client.newCall(org.mockito.ArgumentMatchers.any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(successResponse("{}"));
        when(authTokenStore.loadTokens()).thenReturn(Map.of());

        MalService malService = new MalService();
        ReflectionTestUtils.setField(malService, "okHttpClient", client);
        ReflectionTestUtils.setField(malService, "authTokenStore", authTokenStore);
        ReflectionTestUtils.setField(malService, "baseUrl", "https://api.example.test");
        ReflectionTestUtils.setField(malService, "accessToken", "token");

        malService.updateAnimeProgress("1", 12, "completed");

        verify(client).newCall(argThat(request -> {
            String requestBody = readRequestBody(request);
            return requestBody.contains("status=completed") && requestBody.contains("num_watched_episodes=12");
        }));
    }

    @Test
    void getAnimeListRefreshesExpiredMalTokenAndPersistsNewTokens() throws Exception {
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        AuthTokenStore authTokenStore = mock(AuthTokenStore.class);
        when(client.newCall(org.mockito.ArgumentMatchers.any(Request.class))).thenReturn(call);
        when(call.execute())
                .thenReturn(unauthorizedResponse())
                .thenReturn(successResponse("{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\"}"))
                .thenReturn(successResponse(
                        "{\"data\":[{\"node\":{\"id\":1,\"title\":\"Frieren\",\"num_episodes\":28},"
                                + "\"list_status\":{\"status\":\"watching\",\"num_episodes_watched\":3}}]}"));
        when(authTokenStore.loadTokens()).thenReturn(Map.of());

        MalService malService = new MalService();
        ReflectionTestUtils.setField(malService, "okHttpClient", client);
        ReflectionTestUtils.setField(malService, "authTokenStore", authTokenStore);
        ReflectionTestUtils.setField(malService, "baseUrl", "https://api.example.test");
        ReflectionTestUtils.setField(malService, "tokenUrl", "https://auth.example.test/token");
        ReflectionTestUtils.setField(malService, "clientId", "client-id");
        ReflectionTestUtils.setField(malService, "clientSecret", "client-secret");
        ReflectionTestUtils.setField(malService, "accessToken", "expired-token");
        ReflectionTestUtils.setField(malService, "refreshToken", "refresh-token");

        List<java.util.Map<String, Object>> animeList = malService.getAnimeList();

        assertEquals(1, animeList.size());
        assertEquals("new-access", ReflectionTestUtils.getField(malService, "accessToken"));
        assertEquals("new-refresh", ReflectionTestUtils.getField(malService, "refreshToken"));
        verify(authTokenStore).saveMalTokens("new-access", "new-refresh");
    }

    private static Response successResponse(String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://api.example.test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, okhttp3.MediaType.parse("application/json")))
                .build();
    }

    private static Response errorResponse() {
        return new Response.Builder()
                .request(new Request.Builder().url("https://api.example.test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Internal Server Error")
                .body(ResponseBody.create("", okhttp3.MediaType.parse("application/json")))
                .build();
    }

    private static Response unauthorizedResponse() {
        return new Response.Builder()
                .request(new Request.Builder().url("https://api.example.test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body(ResponseBody.create("", okhttp3.MediaType.parse("application/json")))
                .build();
    }

    private static String readRequestBody(Request request) {
        okio.Buffer buffer = new okio.Buffer();
        try {
            if (request.body() != null) {
                request.body().writeTo(buffer);
            }
            return buffer.readUtf8();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
