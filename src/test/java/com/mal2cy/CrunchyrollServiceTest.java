
package com.mal2cy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.mal2cy.service.CrunchyrollService;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class CrunchyrollServiceTest {

    @Test
    void authenticateStoresAccessTokenAndAccountId() throws Exception {
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        when(client.newCall(org.mockito.ArgumentMatchers.any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(successResponse(
                "{\"access_token\":\"secret-token\",\"account_id\":\"account-123\"}"));

        CrunchyrollService crunchyrollService = new CrunchyrollService();
        ReflectionTestUtils.setField(crunchyrollService, "okHttpClient", client);
        ReflectionTestUtils.setField(crunchyrollService, "baseUrl", "https://cr.example.test");
        ReflectionTestUtils.setField(crunchyrollService, "authUrl", "/auth");
        ReflectionTestUtils.setField(crunchyrollService, "clientId", "client");
        ReflectionTestUtils.setField(crunchyrollService, "deviceId", "device");
        ReflectionTestUtils.setField(crunchyrollService, "etpRt", "cookie");
        ReflectionTestUtils.setField(crunchyrollService, "deviceType", "Chrome on Chrome OS");

        crunchyrollService.authenticate();

        assertEquals("secret-token", crunchyrollService.getAccessToken());
        assertEquals("account-123", crunchyrollService.getAccountId());
        assertEquals("account-123", ReflectionTestUtils.getField(crunchyrollService, "accountId"));
        verify(client).newCall(argThat(request ->
                "etp_rt=cookie".equals(request.header("Cookie"))));
        verify(client).newCall(argThat(request -> {
            String requestBody = readRequestBody(request);
            return requestBody.contains("device_type=Chrome%20on%20Chrome%20OS");
        }));
    }

    @Test
    void getWatchlistParsesWatchlistItems() throws Exception {
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        when(client.newCall(org.mockito.ArgumentMatchers.any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(successResponse(
                "{\"data\":[{\"panel\":{\"episode_metadata\":{\"series_id\":\"series-1\",\"series_title\":\"Solo Leveling\"}}}]}"));

        CrunchyrollService crunchyrollService = new CrunchyrollService();
        ReflectionTestUtils.setField(crunchyrollService, "okHttpClient", client);
        ReflectionTestUtils.setField(crunchyrollService, "baseUrl", "https://cr.example.test");
        ReflectionTestUtils.setField(crunchyrollService, "watchlistUrl", "/watchlist/{accountId}");
        ReflectionTestUtils.setField(crunchyrollService, "accessToken", "secret-token");
        ReflectionTestUtils.setField(crunchyrollService, "accountId", "account-123");

        List<Map<String, Object>> watchlist = crunchyrollService.getWatchlist();

        assertEquals(1, watchlist.size());
        assertEquals("series-1", watchlist.get(0).get("seriesId"));
        assertEquals("Solo Leveling", watchlist.get(0).get("title"));
    }

    @Test
    void getWatchlistReauthenticatesAfterUnauthorizedResponse() throws Exception {
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        when(client.newCall(org.mockito.ArgumentMatchers.any(Request.class))).thenReturn(call);
        when(call.execute())
                .thenReturn(successResponse("{\"access_token\":\"first-token\",\"account_id\":\"account-123\"}"))
                .thenReturn(errorResponse())
                .thenReturn(successResponse("{\"access_token\":\"second-token\",\"account_id\":\"account-123\"}"))
                .thenReturn(successResponse(
                        "{\"data\":[{\"panel\":{\"episode_metadata\":{\"series_id\":\"series-1\",\"series_title\":\"Solo Leveling\"}}}]}"));

        CrunchyrollService crunchyrollService = new CrunchyrollService();
        ReflectionTestUtils.setField(crunchyrollService, "okHttpClient", client);
        ReflectionTestUtils.setField(crunchyrollService, "baseUrl", "https://cr.example.test");
        ReflectionTestUtils.setField(crunchyrollService, "authUrl", "/auth");
        ReflectionTestUtils.setField(crunchyrollService, "watchlistUrl", "/watchlist/{accountId}");
        ReflectionTestUtils.setField(crunchyrollService, "clientId", "client");
        ReflectionTestUtils.setField(crunchyrollService, "deviceId", "device");
        ReflectionTestUtils.setField(crunchyrollService, "etpRt", "cookie");
        ReflectionTestUtils.setField(crunchyrollService, "deviceType", "Chrome on Chrome OS");

        List<Map<String, Object>> watchlist = crunchyrollService.getWatchlist();

        assertEquals(1, watchlist.size());
        assertEquals("second-token", crunchyrollService.getAccessToken());
        verify(client, times(4)).newCall(org.mockito.ArgumentMatchers.any(Request.class));
    }

    @Test
    void authenticateThrowsWhenCrunchyrollReturnsFailure() throws Exception {
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        when(client.newCall(org.mockito.ArgumentMatchers.any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(errorResponse());

        CrunchyrollService crunchyrollService = new CrunchyrollService();
        ReflectionTestUtils.setField(crunchyrollService, "okHttpClient", client);
        ReflectionTestUtils.setField(crunchyrollService, "baseUrl", "https://cr.example.test");
        ReflectionTestUtils.setField(crunchyrollService, "authUrl", "/auth");
        ReflectionTestUtils.setField(crunchyrollService, "clientId", "client");
        ReflectionTestUtils.setField(crunchyrollService, "deviceId", "device");
        ReflectionTestUtils.setField(crunchyrollService, "deviceType", "Chrome on Chrome OS");

        assertThrows(IOException.class, crunchyrollService::authenticate);
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

    private static Response successResponse(String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://cr.example.test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, okhttp3.MediaType.parse("application/json")))
                .build();
    }

    private static Response errorResponse() {
        return new Response.Builder()
                .request(new Request.Builder().url("https://cr.example.test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body(ResponseBody.create("", okhttp3.MediaType.parse("application/json")))
                .build();
    }
}
