package io.github.skippedaga.tinytsdb.console;

import io.github.skippedaga.tinytsdb.Grain;
import io.github.skippedaga.tinytsdb.Increment;
import io.github.skippedaga.tinytsdb.Labels;
import io.github.skippedaga.tinytsdb.TinyTsdb;
import io.github.skippedaga.tinytsdb.Window;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A console on made-up card payments, for looking at the page: {@code java ConsoleDemo [port]}, then open
 * http://localhost:8090/tinytsdb/#card=c-1042&amp;currency=EUR. It keeps writing a payment every 400 ms.
 */
public final class ConsoleDemo {

    private ConsoleDemo() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8090;
        ConsoleMetrics metrics = new ConsoleMetrics();
        TinyTsdb db = TinyTsdb.builder()
                .zone(ZoneId.of("Europe/Kyiv"))
                .tier(Grain.MINUTE, Duration.ofHours(26))
                .tier(Grain.HOUR, Duration.ofDays(32))
                .tier(Grain.DAY, Duration.ofDays(400))
                .listener(metrics)
                .build();

        Random random = new Random(3);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 4000; i++) {
            pay(db, random, "c-" + (1000 + random.nextInt(60)),
                    now - (long) (Math.pow(random.nextDouble(), 2) * 30 * 86_400_000L));
        }

        ConsoleConfig config = ConsoleConfig.builder()
                .enabled(true)
                .refreshSeconds(2)
                .title("Card counters")
                .metric(ConsoleConfig.Metric.count("n_total", "Payments"))
                .metric(ConsoleConfig.Metric.count("n_ok_total", "Approved"))
                .metric(ConsoleConfig.Metric.count("n_fail_total", "Declined"))
                .metric(ConsoleConfig.Metric.amount("amount_total", "Amount", "currency"))
                .seriesLabel("card")
                .seriesLabel("currency")
                .browseLabel("merchant")
                .window("15m", "15 minutes", Window.rolling(15, Grain.MINUTE))
                .window("1h", "1 hour", Window.rolling(60, Grain.MINUTE))
                .window("24h", "24 hours", Window.rolling(1440, Grain.MINUTE))
                .window("7d", "7 days", Window.rolling(168, Grain.HOUR))
                .window("30d", "30 days", Window.rolling(720, Grain.HOUR))
                .anchored("first7d", "First 7 days of the card", "n_ok_total", Duration.ofDays(7))
                .text("label_card", "Card")
                .text("label_currency", "Currency")
                .text("label_merchant", "Merchant")
                .example("Spend by merchant, EUR", "What EUR cards paid at each merchant, net of refunds. The red "
                        + "figure under a cell is what was refunded in that window.", "amount_total",
                        List.of("merchant"), Map.of("currency", "EUR"))
                .example("Declines by merchant", "Declined payments per merchant. Declines that grow in the short "
                        + "windows but not the long ones started recently.", "n_fail_total", List.of("merchant"),
                        Map.of())
                .example("Busiest cards", "Payments per card, most active in the last column first: the cards to "
                        + "look at for unusual activity.", "n_total", List.of("card"), Map.of())
                .example("Cards at each merchant", "Two labels at once: a row for every merchant and card that paid "
                        + "there. The same cube as the others, cut finer.", "n_total", List.of("merchant", "card"),
                        Map.of())
                .example("Amount by currency", "One row per currency. Amounts are never added across currencies, so "
                        + "the total stays empty.", "amount_total", List.of("currency"), Map.of())
                .build();
        ConsoleHttpHandler.serve(new ConsoleApi(db, config, metrics), new InetSocketAddress("127.0.0.1", port),
                "/tinytsdb", exchange -> true);
        System.out.println("http://127.0.0.1:" + port + "/tinytsdb/#card=c-1042&currency=EUR");

        while (true) { // keep paying, half of it with the card on the link, so the page has something to refresh
            Thread.sleep(400);
            pay(db, random, random.nextBoolean() ? "c-1042" : "c-" + (1000 + random.nextInt(60)),
                    System.currentTimeMillis());
        }
    }

    private static final String[] MERCHANTS = {"m-coffee", "m-books", "m-travel", "m-games", "m-grocery"};

    private static void pay(TinyTsdb db, Random random, String card, long at) {
        String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
        long amount = 500 + random.nextInt(20_000);
        boolean ok = random.nextInt(10) < 8;
        String currency = card.compareTo("c-1050") >= 0 ? "USD" : "EUR"; // a card pays in one currency
        Labels l = Labels.of("card", card, "currency", currency, "merchant", merchant);
        db.write(List.of(
                Increment.of("n_total", l, at, 1),
                Increment.of(ok ? "n_ok_total" : "n_fail_total", l, at, 1),
                Increment.of("amount_total", l, at, amount)));
        if (ok && random.nextInt(12) == 0) { // a refund, booked at the payment's time
            db.write(List.of(Increment.of("amount_total", l, at, -amount / 2)));
        }
    }
}
