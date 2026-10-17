package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.time.DateUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Neoxa quotes this chain's coin against USDC only, so USD is the one currency it offers.
 */
public class NeoxaExchangeSourceTest {
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");

    //Trimmed from https://neoxa.exchange/api/exchange/ticker/BTCB2_USDC
    private static final String TICKER = "{\"success\":true,\"pair\":\"BTCB2_USDC\",\"ticker\":{\"lastPrice\":737.02,\"change\":-39.34,\"high24h\":820.37,\"low24h\":697,\"volume24h\":434.86,\"bestBid\":737.02,\"bestAsk\":741.46}}";
    //Trimmed from https://neoxa.exchange/api/exchange/candles/BTCB2_USDC?interval=1d&limit=3
    private static final String CANDLES = "{\"success\":true,\"pair\":\"BTCB2_USDC\",\"interval\":\"1d\",\"candles\":[" +
            "{\"time\":1788220800,\"open\":13.515415,\"high\":220,\"low\":13.515415,\"close\":90,\"volume\":442.69}," +
            "{\"time\":1788307200,\"open\":87,\"high\":299.6,\"low\":80,\"close\":250.5,\"volume\":100.1}," +
            "{\"time\":1788393600,\"open\":250.5,\"high\":300,\"low\":240,\"close\":280,\"volume\":50.2}]}";

    @Test
    public void testOnlyUsdIsOffered() {
        Assertions.assertEquals(List.of(USD), ExchangeSource.NEOXA.getSupportedCurrencies());

        //Answered without a request, since there is nothing to ask for
        Assertions.assertNull(ExchangeSource.NEOXA.getExchangeRate(EUR));
        Assertions.assertTrue(ExchangeSource.NEOXA.getHistoricalExchangeRates(EUR, new Date(0), new Date()).isEmpty());
    }

    @Test
    public void testTickerParses() throws Exception {
        ExchangeSource.NeoxaTicker ticker = new ObjectMapper().readValue(TICKER, ExchangeSource.NeoxaTicker.class);
        Assertions.assertEquals(737.02, ticker.ticker.lastPrice);
    }

    @Test
    public void testCandlesParseToDailyCloses() throws Exception {
        ExchangeSource.NeoxaCandles candles = new ObjectMapper().readValue(CANDLES, ExchangeSource.NeoxaCandles.class);
        Date start = localMidnight(LocalDate.of(2026, 9, 1));
        Date end = localMidnight(LocalDate.of(2026, 9, 3));

        Map<Date, Double> rates = ExchangeSource.toDailyRates(candles, start, end);
        Assertions.assertEquals(3, rates.size());
        Assertions.assertEquals(90.0, rates.get(localMidnight(LocalDate.of(2026, 9, 1))));
        Assertions.assertEquals(280.0, rates.get(localMidnight(LocalDate.of(2026, 9, 3))));

        //Candles outside the requested range are left out
        Assertions.assertEquals(1, ExchangeSource.toDailyRates(candles, end, end).size());
    }

    @Test
    public void testCandlesAreKeyedByTheirUtcDateInEveryZone() throws Exception {
        //A candle opens at UTC midnight. West of UTC that instant falls on the previous local day, and truncating it
        //there would give a transaction on 1 Sep the close of 31 Aug's candle, or none at all
        ExchangeSource.NeoxaCandles candles = new ObjectMapper().readValue(CANDLES, ExchangeSource.NeoxaCandles.class);
        TimeZone defaultZone = TimeZone.getDefault();
        try {
            for(String zone : new String[] {"America/Los_Angeles", "UTC", "Europe/Madrid", "Pacific/Auckland"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                Date txDate = Date.from(LocalDate.of(2026, 9, 2).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant());
                Map<Date, Double> rates = ExchangeSource.toDailyRates(candles, localMidnight(LocalDate.of(2026, 8, 31)), localMidnight(LocalDate.of(2026, 9, 4)));

                Assertions.assertEquals(250.5, rates.get(DateUtils.truncate(txDate, Calendar.DAY_OF_MONTH)), zone);
            }
        } finally {
            TimeZone.setDefault(defaultZone);
        }
    }

    @Test
    public void testCandlesLimitCoversAllDataAndNothingElse() {
        //The count depends only on today, never on the wallet, so it cannot tell Neoxa when its first transaction was.
        //On 17 Oct 46 whole days have passed since 1 Sep, and 47 candles exist, 1 Sep to 17 Oct
        Assertions.assertEquals(48, ExchangeSource.getCandlesLimit(Instant.parse("2026-10-17T12:00:00Z")));
        Assertions.assertEquals(2, ExchangeSource.getCandlesLimit(Instant.parse("2026-09-01T08:00:00Z")));
    }

    @Test
    public void testOnlyFinitePositivePricesAreUsed() {
        Assertions.assertEquals(737.02, ExchangeSource.toValidRate(737.02));
        Assertions.assertNull(ExchangeSource.toValidRate(null));
        Assertions.assertNull(ExchangeSource.toValidRate(0.0));
        Assertions.assertNull(ExchangeSource.toValidRate(-1.0));
        Assertions.assertNull(ExchangeSource.toValidRate(Double.POSITIVE_INFINITY));
        Assertions.assertNull(ExchangeSource.toValidRate(Double.NaN));
    }

    @Test
    public void testInvalidClosesAreLeftOut() throws Exception {
        //1e400 does not fit in a double and parses as Infinity
        String candlesJson = "{\"candles\":[{\"time\":1788220800,\"close\":1e400},{\"time\":1788307200,\"close\":0},{\"time\":1788393600,\"close\":280}]}";
        ExchangeSource.NeoxaCandles candles = new ObjectMapper().readValue(candlesJson, ExchangeSource.NeoxaCandles.class);

        Map<Date, Double> rates = ExchangeSource.toDailyRates(candles, localMidnight(LocalDate.of(2026, 9, 1)), localMidnight(LocalDate.of(2026, 9, 3)));
        Assertions.assertEquals(Map.of(localMidnight(LocalDate.of(2026, 9, 3)), 280.0), rates);
    }

    private static Date localMidnight(LocalDate date) {
        return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
