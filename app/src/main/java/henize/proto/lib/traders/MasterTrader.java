/* Proto - Economy Simulator
Copyright (C) 2024 Joshua Henize

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with this program.
If not, see https://www.gnu.org/licenses/. */
package henize.proto.lib.traders;


import android.widget.EditText;

import java.io.Serializable;

import henize.proto.lib.commodities.Coal;
import henize.proto.lib.commodities.Commodity;
import henize.proto.lib.commodities.Food;
import henize.proto.lib.commodities.Iron;
import henize.proto.lib.commodities.Tool;
import henize.proto.lib.sim.Access;

/**
 * Created by ACR411 on 12/13/2017.
 */

public class MasterTrader extends Trader implements Serializable {
    public FoodTrader foodTrader;
    public ToolTrader toolTrader;
    public CoalTrader coalTrader;
    public IronTrader ironTrader;

    public String f_buyCond, f_sellCond;
    public long fb_setValue, fs_setValue;
    public boolean fs_enabled, fb_enabled;

    public String t_buyCond, t_sellCond;
    public long tb_setValue, ts_setValue;
    public boolean ts_enabled, tb_enabled;

    public String c_buyCond, c_sellCond;
    public long cb_setValue, cs_setValue;
    public boolean cs_enabled, cb_enabled;

    public String i_buyCond, i_sellCond;
    public long ib_setValue, is_setValue;
    public boolean is_enabled, ib_enabled;

    public EditText buyOrderDisplay, sellorderDisplay;

    MasterTrader _this;
    public MasterTrader(Access access) {
        super(access);
        _this = this;
        _this.account.cancel();
        _this.account.close();
        _this.account = access.taxAccount;
        _this.address = access.sim.map.GenerateAddress(7, access.config.msAdd);

        f_buyCond = f_sellCond = t_buyCond = t_sellCond = c_buyCond = c_sellCond = i_buyCond = i_sellCond = MarketOrder.MARKET;
        foodTrader = new FoodTrader(access, "MASTER") {
            boolean init = true;

            public void masterInit() {
                foodTrader.account.cancel();
                foodTrader.account.close();
                foodTrader.account = _this.account;
                access.sim.map.RemoveAddress(foodTrader.address, 2);
                foodTrader.address = _this.address;
                access.dir.add(ComType.FOOD, this);
            }
            @Override
            public void setBuyCond(String buyCond) {
                f_buyCond = buyCond;
            }
            @Override
            public void setSellCond(String sellCond) {
                f_sellCond = sellCond;
            }
            @Override
            public void setBuyValues(int quant, long price) {
                fb_setValue = price;
            }
            @Override
            public void setSellValues(int quanit, long price) {
                fs_setValue = price;
            }
            @Override
            public void buyEnabled(boolean enabled) {
                fb_enabled = enabled;
            }
            @Override
            public void sellEnabled(boolean enabled) {
                fs_enabled = enabled;
            }
            @Override
            public boolean act() {
                if(init) {
                    masterInit();
                    init = false;
                    return false;
                }
                execSpecialPrioritySystem();
                MarketOrder.updateOffer(this, ComType.FOOD, fs_enabled, f_sellCond, fs_setValue);
                sale = false;
                active = false;
                foodTrader.stock();
                return active;
            }

            @Override
            protected boolean stock() {

                if (!fb_enabled || access.sim.autopilot().ownsBuy(ComType.FOOD)) return false;
                if (MarketOrder.matches(f_buyCond, access.dir.getAveragePriceOf(ComType.FOOD), fb_setValue)) {
                    buy(ComType.FOOD, Integer.MAX_VALUE);
                }
                return true;
            }

            @Override
            protected boolean buy(ComType type, int quant) {
                Trader t = access.dir.getCheapestTraderOf(type, false);
                return !(t == null || !t.sellTo(this, account, quant));
            }

            @Override
            protected void receive(Commodity c) {
                super.receive(c);
                if(c instanceof Food){
                    com.push(c);
                }

            }
        }; foodTrader.act();

        toolTrader = new ToolTrader(access, "MASTER") {
            boolean init = true;

            public void masterInit() {
                toolTrader.account.cancel();
                toolTrader.account.close();
                toolTrader.account = _this.account;
                access.sim.map.RemoveAddress(toolTrader.address, 3);
                toolTrader.address = _this.address;
                access.dir.add(ComType.TOOL, this);
            }
            @Override
            public void setBuyCond(String buyCond) {
                t_buyCond = buyCond;
            }
            @Override
            public void setSellCond(String sellCond) {
                t_sellCond = sellCond;
            }
            @Override
            public void setBuyValues(int quant, long price) {
                tb_setValue = price;
            }
            @Override
            public void setSellValues(int quanit, long price) {
                ts_setValue = price;
            }
            @Override
            public void buyEnabled(boolean enabled) {
                tb_enabled = enabled;
            }
            @Override
            public void sellEnabled(boolean enabled) {
                ts_enabled = enabled;
            }

            @Override
            public boolean act() {
                if(init) {
                    masterInit();
                    init = false;
                    return false;
                }
                execSpecialPrioritySystem();
                MarketOrder.updateOffer(this, ComType.TOOL, ts_enabled, t_sellCond, ts_setValue);
                sale = false;
                active = false;
                toolTrader.stock();
                return active;
            }

            @Override
            protected boolean stock() {

                if (!tb_enabled || access.sim.autopilot().ownsBuy(ComType.TOOL)) return false;
                if (MarketOrder.matches(t_buyCond, access.dir.getAveragePriceOf(ComType.TOOL), tb_setValue)) {
                    buy(ComType.TOOL, Integer.MAX_VALUE);
                }
                return true;
            }

            @Override
            protected boolean buy(ComType type, int quant) {
                Trader t = access.dir.getCheapestTraderOf(type, false);
                return !(t == null || !t.sellTo(this, account, quant));
            }

            @Override
            protected void receive(Commodity c) {
                super.receive(c);
                if(c instanceof Tool){
                    com.push(c);
                }

            }
        }; toolTrader.act();

        coalTrader = new CoalTrader(access, "MASTER") {
            boolean init = true;

            public void masterInit() {
                coalTrader.account.cancel();
                coalTrader.account.close();
                coalTrader.account = _this.account;
                access.sim.map.RemoveAddress(coalTrader.address, 4);
                coalTrader.address = _this.address;
                access.dir.add(ComType.COAL, this);
            }
            @Override
            public void setBuyCond(String buyCond) {
                c_buyCond = buyCond;
            }
            @Override
            public void setSellCond(String sellCond) {
                c_sellCond = sellCond;
            }
            @Override
            public void setBuyValues(int quant, long price) {
                cb_setValue = price;
            }
            @Override
            public void setSellValues(int quanit, long price) {
                cs_setValue = price;
            }
            @Override
            public void buyEnabled(boolean enabled) {
                cb_enabled = enabled;
            }
            @Override
            public void sellEnabled(boolean enabled) {
                cs_enabled = enabled;
            }

            @Override
            public boolean act() {
                if(init) {
                    masterInit();
                    init = false;
                    return false;
                }
                execSpecialPrioritySystem();
                MarketOrder.updateOffer(this, ComType.COAL, cs_enabled, c_sellCond, cs_setValue);
                sale = false;
                active = false;
                coalTrader.stock();
                return active;
            }

            @Override
            protected boolean stock() {

                if (!cb_enabled || access.sim.autopilot().ownsBuy(ComType.COAL)) return false;
                if (MarketOrder.matches(c_buyCond, access.dir.getAveragePriceOf(ComType.COAL), cb_setValue)) {
                    buy(ComType.COAL, Integer.MAX_VALUE);
                }
                return true;
            }

            @Override
            protected boolean buy(ComType type, int quant) {
                Trader t = access.dir.getCheapestTraderOf(type, false);
                return !(t == null || !t.sellTo(this, account, quant));
            }

            @Override
            protected void receive(Commodity c) {
                super.receive(c);
                if(c instanceof Coal){
                    com.push(c);
                }

            }
        }; coalTrader.act();

        ironTrader = new IronTrader(access, "MASTER") {
            boolean init = true;

            public void masterInit() {
                ironTrader.account.cancel();
                ironTrader.account.close();
                ironTrader.account = _this.account;
                access.sim.map.RemoveAddress(ironTrader.address, 5);
                ironTrader.address = _this.address;
                access.dir.add(ComType.IRON, this);
            }
            @Override
            public void setBuyCond(String buyCond) {
                i_buyCond = buyCond;
            }
            @Override
            public void setSellCond(String sellCond) {
                i_sellCond = sellCond;
            }
            @Override
            public void setBuyValues(int quant, long price) {
               ib_setValue = price;
            }
            @Override
            public void setSellValues(int quanit, long price) {
                is_setValue = price;
            }
            @Override
            public void buyEnabled(boolean enabled) {
                ib_enabled = enabled;
            }
            @Override
            public void sellEnabled(boolean enabled) {
                is_enabled = enabled;
            }

            @Override
            public boolean act() {
                if(init) {
                    masterInit();
                    init = false;
                    return false;
                }
                execSpecialPrioritySystem();
                MarketOrder.updateOffer(this, ComType.IRON, is_enabled, i_sellCond, is_setValue);
                sale = false;
                active = false;
                ironTrader.stock();
                return active;
            }

            @Override
            protected boolean stock() {

                if (!ib_enabled || access.sim.autopilot().ownsBuy(ComType.IRON)) return false;
                if (MarketOrder.matches(i_buyCond, access.dir.getAveragePriceOf(ComType.IRON), ib_setValue)) {
                    buy(ComType.IRON, Integer.MAX_VALUE);
                }
                return true;
            }

            @Override
            protected boolean buy(ComType type, int quant) {
                Trader t = access.dir.getCheapestTraderOf(type, false);
                return !(t == null || !t.sellTo(this, account, quant));
            }

            @Override
            protected void receive(Commodity c) {
                super.receive(c);
                if(c instanceof Iron){
                    com.push(c);
                }

            }
        }; ironTrader.act();

    }

    @Override
    public boolean act() {
        foodTrader.act();
        toolTrader.act();
        coalTrader.act();
        ironTrader.act();

        return true;
    }
}
