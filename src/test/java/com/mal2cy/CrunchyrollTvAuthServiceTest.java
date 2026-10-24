package com.mal2cy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.mal2cy.service.AuthTokenStore;
import com.mal2cy.service.CrunchyrollService;
import com.mal2cy.service.CrunchyrollTvAuthService;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class CrunchyrollTvAuthServiceTest {

    private final OkHttpClient client = mock(OkHttpClient.class);
    private final Call call = mock(Call.class);
    private final AuthTokenStore authTokenStore = mock(AuthTokenStore.class);

    @Test
    void pollReturnsNullWhileCodeIsNotEnteredYet() throws Exception {
        when(client.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response(204, ""));

        assertNull(newTvService().pollDeviceToken("device-code", "device-1"));
        verify(authTokenStore, never()).saveValue(anyString(), anyString());
    }

    @Test
    void pollSavesRefreshTokenAndDeviceIdOnceActivated() throws Exception {
        when(client.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response(200,
                "{\"access_token\":\"access\",\"refresh_token\":\"refresh-1\",\"account_id\":\"acc\"}"));

        CrunchyrollTvAuthService.TokenResult result = newTvService().pollDeviceToken("device-code", "device-1");

        assertEquals("access", result.accessToken());
        assertEquals("acc", result.accountId());
        verify(authTokenStore).saveValue("crunchyrollTvRefreshToken", "refresh-1");
        verify(authTokenStore).saveValue("crunchyrollTvDeviceId", "device-1");
        verify(client).newCall(argThat(request -> request.url().toString().endsWith("/auth/v1/device/token")
                && "Basic tv-client".equals(request.header("Authorization"))));
    }

    @Test
    void refreshUsesSavedTokenAndSavesTheRotatedOne() throws Exception {
        when(authTokenStore.loadValue("crunchyrollTvRefreshToken")).thenReturn("refresh-1");
        when(authTokenStore.loadValue("crunchyrollTvDeviceId")).thenReturn("device-1");
        when(client.newCall(any(Request.class))).thenReturn(call);
        when(call.execute())
                .thenReturn(response(200, "{\"access_token\":\"access\",\"refresh_token\":\"refresh-2\"}"))
                .thenReturn(response(200, "{\"account_id\":\"acc-from-me\"}"));

        CrunchyrollTvAuthService.TokenResult result = newTvService().refresh();

        assertEquals("acc-from-me", result.accountId());
        verify(authTokenStore).saveValue("crunchyrollTvRefreshToken", "refresh-2");
        verify(client).newCall(argThat(request -> {
            String body = readBody(request);
            return body.contains("grant_type=refresh_token") && body.contains("refresh_token=refresh-1")
                    && body.contains("device_id=device-1") && body.contains("device_type=ANDROIDTV");
        }));
    }

    @Test
    void refreshFailsWithoutActivation() {
        assertThrows(IOException.class, () -> newTvService().refresh());
    }

    @Test
    void crunchyrollLoginPrefersActivatedTvLogin() throws Exception {
        CrunchyrollTvAuthService tvAuthService = mock(CrunchyrollTvAuthService.class);
        when(tvAuthService.isActivated()).thenReturn(true);
        when(tvAuthService.refresh()).thenReturn(new CrunchyrollTvAuthService.TokenResult("tv-access", "acc"));

        CrunchyrollService service = newCrunchyrollService(tvAuthService);
        service.authenticate();

        assertEquals("tv-access", service.getAccessToken());
        verify(client, never()).newCall(any(Request.class));
    }

    @Test
    void crunchyrollLoginFallsBackToCookieWhenTvLoginFails() throws Exception {
        CrunchyrollTvAuthService tvAuthService = mock(CrunchyrollTvAuthService.class);
        when(tvAuthService.isActivated()).thenReturn(true);
        when(tvAuthService.refresh()).thenThrow(new IOException("invalid_grant"));
        when(client.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response(200, "{\"access_token\":\"cookie-access\",\"account_id\":\"acc\"}"));

        CrunchyrollService service = newCrunchyrollService(tvAuthService);
        service.authenticate();

        assertEquals("cookie-access", service.getAccessToken());
        verify(client).newCall(argThat(request -> "etp_rt=cookie".equals(request.header("Cookie"))));
    }

    private CrunchyrollTvAuthService newTvService() {
        CrunchyrollTvAuthService service = new CrunchyrollTvAuthService();
        ReflectionTestUtils.setField(service, "okHttpClient", client);
        ReflectionTestUtils.setField(service, "authTokenStore", authTokenStore);
        ReflectionTestUtils.setField(service, "baseUrl", "https://cr.example.test");
        ReflectionTestUtils.setField(service, "authUrl", "/auth/v1/token");
        ReflectionTestUtils.setField(service, "deviceCodeUrl", "/auth/v1/device/code");
        ReflectionTestUtils.setField(service, "deviceTokenUrl", "/auth/v1/device/token");
        ReflectionTestUtils.setField(service, "accountUrl", "/accounts/v1/me");
        ReflectionTestUtils.setField(service, "tvClientAuth", "tv-client");
        ReflectionTestUtils.setField(service, "tvUserAgent", "Crunchyroll/ANDROIDTV");
        ReflectionTestUtils.setField(service, "tvDeviceName", "mal2cy");
        return service;
    }

    private CrunchyrollService newCrunchyrollService(CrunchyrollTvAuthService tvAuthService) {
        CrunchyrollService service = new CrunchyrollService();
        ReflectionTestUtils.setField(service, "okHttpClient", client);
        ReflectionTestUtils.setField(service, "tvAuthService", tvAuthService);
        ReflectionTestUtils.setField(service, "baseUrl", "https://cr.example.test");
        ReflectionTestUtils.setField(service, "authUrl", "/auth");
        ReflectionTestUtils.setField(service, "clientId", "client");
        ReflectionTestUtils.setField(service, "deviceId", "device");
        ReflectionTestUtils.setField(service, "etpRt", "cookie");
        ReflectionTestUtils.setField(service, "deviceType", "Chrome on Chrome OS");
        return service;
    }

    private static Response response(int code, String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://cr.example.test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("status")
                .body(ResponseBody.create(body, okhttp3.MediaType.parse("application/json")))
                .build();
    }

    private static String readBody(Request request) {
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
