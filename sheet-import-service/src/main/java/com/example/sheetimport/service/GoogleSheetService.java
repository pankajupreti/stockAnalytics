
package com.example.sheetimport.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.*;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;

@Service
public class GoogleSheetService {

    private static final String SPREADSHEET_ID = "18gl-L1GwEmOpopFNKrOwmtbaGWoQy2auG_T3DCrPuBk";
    private static final String APPLICATION_NAME = "Stock Portfolio Tracker";

    @Value("${google.sa.credentials-file}")
    private Resource credentialsResource;




    public List<List<Object>> readSheet(String range) throws Exception {
        Sheets sheets = getSheetsService();
        ValueRange response = sheets.spreadsheets().values().get(SPREADSHEET_ID, range).execute();
        return response.getValues();
    }

    private Sheets getSheetsService() throws Exception {
        // Load credentials from the location configured in properties/env
        try (InputStream inputStream = credentialsResource.getInputStream()) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(inputStream)
                    .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS));

            return new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    JacksonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials)
            )
                    .setApplicationName(APPLICATION_NAME)
                    .build();
        }
    }
    public void appendRows(String sheetName, List<List<Object>> rows) throws Exception {
        Sheets sheets = getSheetsService();

        ValueRange body = new ValueRange()
                .setValues(rows);

        sheets.spreadsheets().values()
                .append(SPREADSHEET_ID, sheetName, body)
                .setValueInputOption("RAW") // or "USER_ENTERED" if you want formulas parsed
                .execute();
    }

    /**
     * Write rows with formulas to a sheet range.
     * Uses USER_ENTERED to parse formulas like =GOOGLEFINANCE(...)
     * Creates the sheet if it doesn't exist.
     */
    public void writeRowsWithFormulas(String range, List<List<Object>> rows) throws Exception {
        Sheets sheets = getSheetsService();

        // Extract sheet name from range (e.g., "MarketCapHelper!A1:B10" -> "MarketCapHelper")
        String sheetName = range.contains("!") ? range.split("!")[0] : range;

        // Ensure the sheet exists
        ensureSheetExists(sheets, sheetName);

        ValueRange body = new ValueRange()
                .setValues(rows);

        sheets.spreadsheets().values()
                .update(SPREADSHEET_ID, range, body)
                .setValueInputOption("USER_ENTERED") // Parse formulas
                .execute();
    }

    /**
     * Create a sheet if it doesn't exist.
     */
    private void ensureSheetExists(Sheets sheets, String sheetName) throws Exception {
        // Get current spreadsheet to check existing sheets
        Spreadsheet spreadsheet = sheets.spreadsheets().get(SPREADSHEET_ID).execute();
        boolean sheetExists = spreadsheet.getSheets().stream()
                .anyMatch(s -> s.getProperties().getTitle().equals(sheetName));

        if (!sheetExists) {
            // Create the sheet
            AddSheetRequest addSheetRequest = new AddSheetRequest()
                    .setProperties(new SheetProperties().setTitle(sheetName));

            BatchUpdateSpreadsheetRequest batchRequest = new BatchUpdateSpreadsheetRequest()
                    .setRequests(Collections.singletonList(
                            new Request().setAddSheet(addSheetRequest)
                    ));

            sheets.spreadsheets().batchUpdate(SPREADSHEET_ID, batchRequest).execute();
        }
    }

    /**
     * Clear a range in the sheet. Silently ignores if sheet doesn't exist.
     */
    public void clearRange(String range) throws Exception {
        try {
            Sheets sheets = getSheetsService();
            sheets.spreadsheets().values()
                    .clear(SPREADSHEET_ID, range, new ClearValuesRequest())
                    .execute();
        } catch (Exception e) {
            // Ignore if sheet doesn't exist - it will be created when writing
            if (!e.getMessage().contains("Unable to parse range")) {
                throw e;
            }
        }
    }

    /**
     * Read values from a specific range (after formulas are calculated).
     */
    public List<List<Object>> readRange(String range) throws Exception {
        Sheets sheets = getSheetsService();
        ValueRange response = sheets.spreadsheets().values()
                .get(SPREADSHEET_ID, range)
                .setValueRenderOption("UNFORMATTED_VALUE") // Get raw values, not formulas
                .execute();
        return response.getValues();
    }
}
