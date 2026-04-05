package com.mal2cy.task;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mal2cy.service.SyncService;

@Component
public class DailySyncTask {

    @Autowired
    private SyncService syncService;

    @Scheduled(cron = "${app.sync.cron}", zone = "${app.sync.zone:Asia/Calcutta}")
    public void runDailySync() {
        syncService.performSync();
    }
}
