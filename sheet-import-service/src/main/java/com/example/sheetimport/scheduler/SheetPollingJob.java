
package com.example.sheetimport.scheduler;

import com.example.sheetimport.model.StockAnalytics;
import com.example.sheetimport.model.StockAnalyticsDto;
import com.example.sheetimport.repository.StockAnalyticsRepository;
import com.example.sheetimport.service.GoogleSheetService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class SheetPollingJob {

    private final GoogleSheetService sheetService;
    private final StockAnalyticsRepository repository;


    public SheetPollingJob(GoogleSheetService sheetService, StockAnalyticsRepository repository) {
        this.sheetService = sheetService;
        this.repository = repository;
    }

    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void pollSheetAndProcess() {
        try {
            List<List<Object>> rows = sheetService.readSheet("Main!A2:M");
            int importedCount = 0;
             for (List<Object> row : rows) {
                if (row.size() >= 10 && isValidRow(row)) {
                    StockAnalytics entity = new StockAnalytics();
                    entity.setTicker(row.get(0).toString());
                    entity.setName(row.get(1).toString());
                    entity.setCmp(parseDouble(row.get(2)));
                    entity.setDailyChange(parseDouble(row.get(3)));
                    entity.setCmp365(parseDouble(row.get(4)));
                    entity.setRank1Year(parseDouble(row.get(5)));
                    entity.setRank1Month(parseDouble(row.get(9)));
                    entity.setRank2Month(parseDouble(row.get(10)));
                    entity.setRank1Week(parseDouble(row.get(11)));
                    entity.setMarketCap(parseDouble(row.get(12)));
                    entity.setLastUpdated(LocalDateTime.now());
                    repository.save(entity);
                    importedCount++;
                }
            }

            System.out.println("Imported " + importedCount + " valid rows from Google Sheets");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isValidRow(List<Object> row) {
        String ticker = row.get(0).toString().trim();
        String cmp = row.get(2).toString().trim();

        return !ticker.isEmpty()
                && !cmp.equalsIgnoreCase("#N/A")
                && !cmp.isEmpty()
                && isNumeric(cmp);
    }

    private boolean isNumeric(String str) {
        try {
            Double.parseDouble(str.replace(",", ""));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Double parseDouble(Object obj) {
        try {
            return obj != null ? Double.parseDouble(obj.toString().replaceAll(",", "")) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
