/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.bank;

import henize.proto.lib.bank.Account.TaxCode;
import henize.proto.lib.sim.Config;

/** The universal rate is stored under the engine's NULL tax code. Rates are percentages. */
public final class TaxRates {
    private TaxRates() {}

    public static boolean isValid(Config config) {
        double universal = config.universalTaxRate;
        if (!(universal >= 0 && universal <= 100)) return false;
        for (TaxCode code : TaxCode.values()) {
            if (code == TaxCode.NULL || code == TaxCode.MASTER) continue;
            double sector = get(config, code);
            if (!(sector >= 0 && universal + sector <= 100)) return false;
        }
        return true;
    }

    public static double get(Config config, TaxCode code) {
        switch (code) {
            case NULL: return config.universalTaxRate;
            case LABOR: return config.laborTaxRate;
            case FOOD: return config.foodTaxRate;
            case TOOL: return config.toolTaxRate;
            case COAL: return config.coalTaxRate;
            case IRON: return config.ironTaxRate;
            case TRANSPORT: return config.transportTaxRate;
            case MASTER: return 0;
            default: throw new AssertionError(code);
        }
    }

    public static void set(Config config, TaxCode code, double rate) {
        switch (code) {
            case NULL: config.universalTaxRate = rate; break;
            case LABOR: config.laborTaxRate = rate; break;
            case FOOD: config.foodTaxRate = rate; break;
            case TOOL: config.toolTaxRate = rate; break;
            case COAL: config.coalTaxRate = rate; break;
            case IRON: config.ironTaxRate = rate; break;
            case TRANSPORT: config.transportTaxRate = rate; break;
            default: throw new IllegalArgumentException("No configurable tax rate for " + code);
        }
    }
}
