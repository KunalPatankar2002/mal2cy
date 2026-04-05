package com.mal2cy.service;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Service
public class CrunchyrollMetadataService {
    @Autowired
    private OkHttpClient okHttpClient;

    @Value("${app.crunchyroll.base-url}")
    private String baseUrl;

    @Value("${app.credentials.crunchyroll-device-id}")
    private String deviceId;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public JsonNode getSeriesMetadata(String accessToken, String seriesId) throws IOException {
        String url = baseUrl + "/content/v2/cms/objects/" + seriesId + "?ratings=true&preferred_audio_language=ja-JP&locale=en-US";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + accessToken)
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Get series metadata failed: " + response);
            return objectMapper.readTree(response.body().string());
        }
    }
}
