
package com.example.sheetimport.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;
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
}
