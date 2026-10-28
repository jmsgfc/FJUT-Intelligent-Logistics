package com.local.smartlogistics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

final class UsageSummary {
    static final BigDecimal ELECTRIC_PRICE = new BigDecimal("0.5333");
    static final BigDecimal WATER_PRICE = new BigDecimal("2.6");

    BigDecimal remainingElectric = BigDecimal.ZERO;
    BigDecimal remainingWater = BigDecimal.ZERO;
    BigDecimal usedElectric = BigDecimal.ZERO;
    BigDecimal usedWater = BigDecimal.ZERO;
    String dateFrom;
    String dateTo;
    final List<MeterTotal> electricMeters = new ArrayList<>();
    final List<DailyRow> dailyRows = new ArrayList<>();

    BigDecimal electricCost() {
        return usedElectric.multiply(ELECTRIC_PRICE);
    }

    BigDecimal waterCost() {
        return usedWater.multiply(WATER_PRICE);
    }

    BigDecimal remainingElectricCost() {
        return remainingElectric.multiply(ELECTRIC_PRICE);
    }

    BigDecimal remainingWaterCost() {
        return remainingWater.multiply(WATER_PRICE);
    }

    static String amount(BigDecimal value) {
        return value == null ? "-" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static final class MeterTotal {
        final String name;
        final String purpose;
        final BigDecimal usage;

        MeterTotal(String name, BigDecimal usage) {
            this.name = name;
            this.usage = usage;
            if (name.contains("表1")) {
                purpose = "阳台、卫生间和插座";
            } else if (name.contains("表2")) {
                purpose = "房间照明和空调";
            } else {
                purpose = "其他用电";
            }
        }

        BigDecimal cost() {
            return usage.multiply(ELECTRIC_PRICE);
        }
    }

    static final class DailyRow {
        final String date;
        BigDecimal electric;
        BigDecimal water;

        DailyRow(String date) {
            this.date = date;
        }

        BigDecimal electricCost() {
            return electric == null ? null : electric.multiply(ELECTRIC_PRICE);
        }

        BigDecimal waterCost() {
            return water == null ? null : water.multiply(WATER_PRICE);
        }
    }
}
