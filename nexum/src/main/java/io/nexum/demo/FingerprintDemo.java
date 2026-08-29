package io.nexum.demo;

import io.nexum.core.Fingerprint;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exercises every comparison the routing rules can express. */
public final class FingerprintDemo {

    static final int SYMBOL = 55;
    static final int SIDE = 54;
    static final int ORDER_QTY = 38;
    static final int ORD_TYPE = 40;
    static final int PRICE = 44;
    static final int SECURITY_EXCHANGE = 207;
    static final int ACCOUNT = 1;

    public static void main(String[] args) {
        Map<Integer, String> order = new LinkedHashMap<>();
        order.put(SYMBOL, "VOD.L");
        order.put(SIDE, "1");
        order.put(ORDER_QTY, "250000");
        order.put(ORD_TYPE, "2");
        order.put(PRICE, "78.50");
        order.put(SECURITY_EXCHANGE, "L");

        System.out.println("message: " + order + "\n");

        check("equals", Fingerprint.of().eq(SIDE, "1").build(), order);
        check("not equals", Fingerprint.of().ne(ORD_TYPE, "1").build(), order);
        check("wildcard suffix", Fingerprint.of().like(SYMBOL, "*.L").build(), order);
        check("regex", Fingerprint.of().regex(SYMBOL, "[A-Z]{3}\\.[A-Z]").build(), order);
        check("greater than", Fingerprint.of().gt(ORDER_QTY, "100000").build(), order);
        check("less than", Fingerprint.of().lt(PRICE, "100").build(), order);
        check("between", Fingerprint.of().gte(PRICE, "50").lte(PRICE, "80").build(), order);
        check("in set", Fingerprint.of().in(SECURITY_EXCHANGE, "L", "N", "O").build(), order);
        check("exists", Fingerprint.of().exists(PRICE).build(), order);
        check("absent", Fingerprint.of().absent(ACCOUNT).build(), order);

        check("multi-tag AND",
                Fingerprint.of()
                        .eq(SECURITY_EXCHANGE, "L")
                        .gt(ORDER_QTY, "100000")
                        .ne(ORD_TYPE, "1")
                        .build(),
                order);

        check("OR alternatives",
                Fingerprint.of()
                        .eq(SECURITY_EXCHANGE, "N")
                        .or()
                        .eq(SECURITY_EXCHANGE, "L")
                        .build(),
                order);

        System.out.println("\n-- a rule that does not match, and why --");
        Fingerprint tooSmall = Fingerprint.of()
                .eq(SECURITY_EXCHANGE, "L")
                .gt(ORDER_QTY, "500000")
                .build();
        System.out.println("rule   : " + tooSmall);
        System.out.println("matched: " + tooSmall.matches(order));
        System.out.println("because: " + tooSmall.explainFailure(order));

        System.out.println("\n-- numeric comparison is by value, not string --");
        Map<Integer, String> small = Map.of(ORDER_QTY, "9");
        Fingerprint over100 = Fingerprint.of().gt(ORDER_QTY, "100").build();
        System.out.println("qty=9 > 100 ? " + over100.matches(small)
                + "   (string compare would say true)");

        System.out.println("\n-- a malformed value fails rather than throwing --");
        Fingerprint qtyOver10 = Fingerprint.of().gt(ORDER_QTY, "10").build();
        System.out.println("qty=\"abc\" > 10 ? "
                + qtyOver10.matches(Map.of(ORDER_QTY, "abc")));
    }

    private static void check(String label, Fingerprint fingerprint, Map<Integer, String> fields) {
        System.out.printf("  %-18s %-5s  %s%n",
                label, fingerprint.matches(fields), fingerprint);
    }
}
