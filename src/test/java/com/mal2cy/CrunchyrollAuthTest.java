package com.mal2cy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.mal2cy.service.AuthTokenStore;
import com.mal2cy.service.CrunchyrollService;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class CrunchyrollAuthTest {

    private final OkHttpClient client = mock(OkHttpClient.class);
    private final Call call = mock(Call.class);
    private final AuthTokenStore authTokenStore = mock(AuthTokenStore.class);

    @Test
    void savesRotatedCookieFromCookieLogin() throws Exception {
        when(client.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response(200,
                "{\"access_token\":\"access\",\"account_id\":\"acc\",\"refresh_token\":\"refresh-1\"}",
                "etp_rt=rotated-cookie; Path=/; HttpOnly"));

        CrunchyrollService service = newService();
        service.authenticate();

        assertEquals("access", service.getAccessToken());
        verify(authTokenStore).saveValue("crunchyrollEtpRt", "rotated-cookie");
        verify(authTokenStore, never()).saveValue(eq("crunchyrollRefreshToken"), anyString());
        verify(client).newCall(argThat(request -> "etp_rt=configured-cookie".equals(request.header("Cookie"))));
    }

    @Test
    void fallsBackToConfiguredCookieWhenSavedCookieIsRejected() throws Exception {
        when(authTokenStore.loadValue("crunchyrollEtpRt")).thenReturn("stale-saved-cookie");
        when(client.newCall(any(Request.class))).thenReturn(call);
        when(call.execute())
                .thenReturn(response(400, "{}", null))
                .thenReturn(response(200, "{\"access_token\":\"access\",\"account_id\":\"acc\"}", null));

        CrunchyrollService service = newService();
        service.authenticate();

        assertEquals("access", service.getAccessToken());
        verify(client, times(2)).newCall(any(Request.class));
        verify(client).newCall(argThat(request -> "etp_rt=stale-saved-cookie".equals(request.header("Cookie"))));
        verify(client).newCall(argThat(request -> "etp_rt=configured-cookie".equals(request.header("Cookie"))));
        verify(authTokenStore, never()).saveValue(anyString(), anyString());
    }

    @Test
    void throwsWhenEveryLoginOptionFails() throws Exception {
        when(client.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response(400, "{}", null));

        CrunchyrollService service = newService();

        assertThrows(IOException.class, service::authenticate);
    }

    private CrunchyrollService newService() {
        CrunchyrollService service = new CrunchyrollService();
        ReflectionTestUtils.setField(service, "okHttpClient", client);
        ReflectionTestUtils.setField(service, "authTokenStore", authTokenStore);
        ReflectionTestUtils.setField(service, "baseUrl", "https://cr.example.test");
        ReflectionTestUtils.setField(service, "authUrl", "/auth");
        ReflectionTestUtils.setField(service, "clientId", "client");
        ReflectionTestUtils.setField(service, "deviceId", "device");
        ReflectionTestUtils.setField(service, "etpRt", "configured-cookie");
        ReflectionTestUtils.setField(service, "deviceType", "Chrome on Chrome OS");
        return service;
    }

    private static Response response(int code, String body, String setCookie) {
        Response.Builder builder = new Response.Builder()
                .request(new Request.Builder().url("https://cr.example.test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code == 200 ? "OK" : "Bad Request")
                .body(ResponseBody.create(body, okhttp3.MediaType.parse("application/json")));
        if (setCookie != null) {
            builder.addHeader("Set-Cookie", setCookie);
        }
        return builder.build();
    }
}
