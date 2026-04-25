package com.example.sheetimport.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;


@Entity
@Table(name = "stock_analytics")
public class StockAnalytics {
    @Id
    private String ticker;


    private String name;
    private Double cmp;
    private Double dailyChange;
    private Double cmp365;
    private Double rank1Year;
    private Double rank1Month;
    private Double rank2Month;
    private Double rank1Week;

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Double getCmp() {
        return cmp;
    }

    public void setCmp(Double cmp) {
        this.cmp = cmp;
    }

    public Double getDailyChange() {
        return dailyChange;
    }

    public void setDailyChange(Double dailyChange) {
        this.dailyChange = dailyChange;
    }

    public Double getCmp365() {
        return cmp365;
    }

    public void setCmp365(Double cmp365) {
        this.cmp365 = cmp365;
    }

    public Double getRank1Year() {
        return rank1Year;
    }

    public void setRank1Year(Double rank1Year) {
        this.rank1Year = rank1Year;
    }

    public Double getRank1Month() {
        return rank1Month;
    }

    public void setRank1Month(Double rank1Month) {
        this.rank1Month = rank1Month;
    }

    public Double getRank2Month() {
        return rank2Month;
    }

    public void setRank2Month(Double rank2Month) {
        this.rank2Month = rank2Month;
    }

    public Double getRank1Week() {
        return rank1Week;
    }

    public void setRank1Week(Double rank1Week) {
        this.rank1Week = rank1Week;
    }

    public Double getMarketCap() {
        return marketCap;
    }

    public void setMarketCap(Double marketCap) {
        this.marketCap = marketCap;
    }

    public LocalDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(LocalDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    private Double marketCap;

    private LocalDateTime lastUpdated;

    // 52-week high/low from Yahoo Finance
    private Double high52Week;
    private Double low52Week;
    private LocalDateTime high52WeekUpdated;

    // Sector and Industry from Yahoo Finance
    private String sector;
    private String industry;
    private LocalDateTime sectorUpdated;

    // Source of the stock data: "SHEET" (imported from Google Sheet) or "YAHOO" (discovered from Yahoo Finance)
    private String source;

    public Double getHigh52Week() {
        return high52Week;
    }

    public void setHigh52Week(Double high52Week) {
        this.high52Week = high52Week;
    }

    public Double getLow52Week() {
        return low52Week;
    }

    public void setLow52Week(Double low52Week) {
        this.low52Week = low52Week;
    }

    public LocalDateTime getHigh52WeekUpdated() {
        return high52WeekUpdated;
    }

    public void setHigh52WeekUpdated(LocalDateTime high52WeekUpdated) {
        this.high52WeekUpdated = high52WeekUpdated;
    }

    public String getSector() {
        return sector;
    }

    public void setSector(String sector) {
        this.sector = sector;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public LocalDateTime getSectorUpdated() {
        return sectorUpdated;
    }

    public void setSectorUpdated(LocalDateTime sectorUpdated) {
        this.sectorUpdated = sectorUpdated;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}