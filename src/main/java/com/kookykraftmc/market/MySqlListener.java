package com.kookykraftmc.market;

import java.util.List;

/**
 * Background task that polls the {@code market_events} table and applies
 * changes to the local server.
 */
public class MySqlListener implements Runnable {

    private final Market market;
    private final MySqlStorageService storageService;
    private volatile boolean running = true;

    public MySqlListener(Market market, MySqlStorageService storageService) {
        this.market = market;
        this.storageService = storageService;
    }

    @Override
    public void run() {
        while (running) {
            List<MarketEvent> events = storageService.pollEvents();
            for (MarketEvent event : events) {
                MarketEventType type = event.getType();
                switch (type) {
                    case BLACKLIST_ADD:
                        market.addIDToBlackList(event.getItem());
                        break;
                    case BLACKLIST_REMOVE:
                        market.rmIDFromBlackList(event.getItem());
                        break;
                    default:
                        break;
                }
                storageService.markProcessed(event.getId());
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                running = false;
                Thread.currentThread().interrupt();
            }
        }
    }
}
