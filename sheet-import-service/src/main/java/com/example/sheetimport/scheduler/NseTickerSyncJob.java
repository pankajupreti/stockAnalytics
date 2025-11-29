package com.example.sheetimport.scheduler;
// SheetImportService: Full logic with polling, parsing, filtering, and Google Sheet integration

// Existing code for polling and saving to DB remains unchanged

// Step 5: Add scheduled job to sync latest NSE tickers to Google Sheet


import com.example.sheetimport.service.GoogleSheetService;
import com.google.api.services.sheets.v4.Sheets;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.api.services.sheets.v4.Sheets;
import java.util.List;

@Component
public class NseTickerSyncJob {

    private final GoogleSheetService sheetService;

    public NseTickerSyncJob(GoogleSheetService sheetService) {
        this.sheetService = sheetService;
    }

 /*   @PostConstruct
    public void testOnceOnStartup() {
        syncNewNseTickers();  // ✅ Run immediately on app start
    }*/

    //@Scheduled(cron = "0 0 2 * * SUN") // Every Sunday at 2:00 AM
  //  @Scheduled(cron = "0 * * * * *") // every minute for quick testing
    public void syncNewNseTickers() {
        try {
            // 1. Download latest NSE ticker list
            URL url = new URL("https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            Set<String> latestTickers = new HashSet<>();

            while ((line = reader.readLine()) != null) {
                String[] tokens = line.split(",");
                if (tokens.length > 1 && !tokens[0].equalsIgnoreCase("SYMBOL")) {
                    latestTickers.add(tokens[0].trim());
                }
            }
            reader.close();

            // 2. Read current tickers from Google Sheet
            List<List<Object>> currentRows = sheetService.readSheet("Main!A2:A");
            Set<String> currentTickers = new HashSet<>();
            for (List<Object> row : currentRows) {
                if (!row.isEmpty()) {
                    currentTickers.add(row.get(0).toString().trim());
                }
            }

            // 3. Compare and find new tickers
            List<List<Object>> newTickerRows = new ArrayList<>();
            for (String ticker : latestTickers) {
                if (!currentTickers.contains(ticker)) {
                    List<Object> newRow = new ArrayList<>();
                    newRow.add(ticker); // Column A
                    newRow.add("NEW");  // Column B: Placeholder name
                    int rowIndex = 2 + currentRows.size() + newTickerRows.size();
                    newTickerRows.add(buildNewTickerRow(ticker, rowIndex));
                }
            }


            if (!newTickerRows.isEmpty()) {
                sheetService.appendRows("Main", newTickerRows);
                System.out.println("Added " + newTickerRows.size() + " new NSE tickers to Google Sheet.");
            } else {
                System.out.println("No new tickers found to add.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }




    // Method to construct formula-filled row for a new ticker
    private List<Object> buildNewTickerRow(String ticker, int rowIndex) {
        List<Object> newRow = new ArrayList<>();
        String fullTicker = "NSE:" + ticker;


        newRow.add(fullTicker); // A: Ticker
        newRow.add("NEW"); // B: Placeholder Name


        // C: CMP (current price)
                newRow.add("=IFERROR(GOOGLEFINANCE(A" + rowIndex + ", \"price\"), \"#N/A\")");
        // D: Daily Change (%)
                newRow.add("=IFERROR(GOOGLEFINANCE(A" + rowIndex + ", \"changepct\"), \"#N/A\")");
        // E: CMP -365 days
                newRow.add("=INDEX(GOOGLEFINANCE(A" + rowIndex + ", \"price\", TODAY()-365), 2, 2)");
        // F: 1-Year % change
                newRow.add("=ROUND(((C" + rowIndex + "-E" + rowIndex + ")/E" + rowIndex + ")*100, 2)");
        // G: Rank 1Y
                newRow.add("#N/A");
        // H: Rank 1m
                newRow.add("=ROUND(((C" + rowIndex + "-G" + rowIndex + ")/G" + rowIndex + ")*100, 2)");
        // I: Rank 2m
                newRow.add("=ROUND(((C" + rowIndex + "-H" + rowIndex + ")/H" + rowIndex + ")*100, 2)");
        // J: Rank 1w
                newRow.add("=ROUND(((C" + rowIndex + "-I" + rowIndex + ")/I" + rowIndex + ")*100, 2)");
        // K: Market cap placeholder
                newRow.add("#N/A");


        return newRow;
    }


}
