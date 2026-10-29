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
import java.util.Random;

/**
 * A console on made-up card payments, for looking at the page: {@code java ConsoleDemo [port]}, then open
 * http://localhost:8090/tinytsdb/#card=c-1042&amp;currency=EUR.
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
        String[] merchants = {"m-coffee", "m-books", "m-travel", "m-games", "m-grocery"};
        for (int i = 0; i < 4000; i++) {
            String card = "c-" + (1000 + random.nextInt(60));
            String merchant = merchants[random.nextInt(merchants.length)];
            long at = now - (long) (Math.pow(random.nextDouble(), 2) * 30 * 86_400_000L);
            long amount = 500 + random.nextInt(20_000);
            boolean ok = random.nextInt(10) < 8;
            Labels l = Labels.of("card", card, "currency", "EUR", "merchant", merchant);
            db.write(List.of(
                    Increment.of("n_total", l, at, 1),
                    Increment.of(ok ? "n_ok_total" : "n_fail_total", l, at, 1),
                    Increment.of("amount_total", l, at, amount)));
            if (ok && random.nextInt(12) == 0) { // a refund, booked at the payment's time
                db.write(List.of(Increment.of("amount_total", l, at, -amount / 2)));
            }
        }

        ConsoleConfig config = ConsoleConfig.builder()
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
                .build();
        ConsoleHttpHandler.serve(new ConsoleApi(db, config, metrics), new InetSocketAddress("127.0.0.1", port),
                "/tinytsdb", exchange -> true);
        System.out.println("http://127.0.0.1:" + port + "/tinytsdb/#card=c-1042&currency=EUR");
    }
}
