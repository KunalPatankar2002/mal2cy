package com.mal2cy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sends phone notifications through ntfy (https://ntfy.sh). Does nothing unless
 * NTFY_TOPIC is set; subscribe to the same topic in the ntfy app to receive them.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    @Autowired
    private OkHttpClient okHttpClient;

    @Value("${app.notify.ntfy-url:https://ntfy.sh}")
    private String ntfyUrl;

    @Value("${app.notify.ntfy-topic:}")
    private String ntfyTopic;

    public boolean isEnabled() {
        return ntfyTopic != null && !ntfyTopic.isBlank();
    }

    public void notifyFailure(String title, String message) {
        send(title, message, "high", "warning");
    }

    public void notifyInfo(String title, String message) {
        send(title, message, "default", "tv");
    }

    private void send(String title, String message, String priority, String tags) {
        if (!isEnabled()) {
            return;
        }
        Request request = new Request.Builder()
                .url(ntfyUrl.replaceAll("/+$", "") + "/" + ntfyTopic.trim())
                .header("Title", title)
                .header("Priority", priority)
                .header("Tags", tags)
                .post(RequestBody.create(message, MediaType.parse("text/plain; charset=utf-8")))
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("ntfy notification failed with HTTP {}", response.code());
            }
        } catch (Exception e) {
            log.warn("ntfy notification failed: {}", e.getMessage());
        }
    }
}
