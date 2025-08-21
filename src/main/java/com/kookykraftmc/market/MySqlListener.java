package com.kookykraftmc.market;

import java.util.List;

/**
 * Task that polls the {@code market_events} table and applies
 * changes to the local server.
 */
public class MySqlListener implements Runnable {

    private final Market market;
    private final MySqlStorageService storageService;

    public MySqlListener(Market market, MySqlStorageService storageService) {
        this.market = market;
        this.storageService = storageService;
    }

    @Override
    public void run() {
        List<MarketEvent> events = storageService.pollEvents();
        for (MarketEvent event : events) {
            String type = event.getType();
            if ("BLACKLIST_ADD".equalsIgnoreCase(type)) {
                market.addIDToBlackList(event.getItem());
            } else if ("BLACKLIST_REMOVE".equalsIgnoreCase(type)) {
                market.rmIDFromBlackList(event.getItem());
            }
            storageService.markProcessed(event.getId());
        }
    }
}
