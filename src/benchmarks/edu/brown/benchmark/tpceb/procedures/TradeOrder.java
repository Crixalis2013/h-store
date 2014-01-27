/***************************************************************************
 *  Copyright (C) 2009 by H-Store Project                                  *
 *  Brown University                                                       *
 *  Massachusetts Institute of Technology                                  *
 *  Yale University                                                        *
 *                                                                         *
 *  Original Version:                                                      *
 *  Zhe Zhang (zhe@cs.brown.edu)                                           *
 *  http://www.cs.brown.edu/~zhe/                                          *
 *                                                                         *
 *  Modifications by:                                                      *
 *  Andy Pavlo (pavlo@cs.brown.edu)                                        *
 *  http://www.cs.brown.edu/~pavlo/                                        *
 *                                                                         *
 *  Modifications by:                                                      *
 *  Alex Kalinin (akalinin@cs.brown.edu)                                   *
 *  http://www.cs.brown.edu/~akalinin/                                     *
 *                                                                         *
 *  Permission is hereby granted, free of charge, to any person obtaining  *
 *  a copy of this software and associated documentation files (the        *
 *  "Software"), to deal in the Software without restriction, including    *
 *  without limitation the rights to use, copy, modify, merge, publish,    *
 *  distribute, sublicense, and/or sell copies of the Software, and to     *
 *  permit persons to whom the Software is furnished to do so, subject to  *
 *  the following conditions:                                              *
 *                                                                         *
 *  The above copyright notice and this permission notice shall be         *
 *  included in all copies or substantial portions of the Software.        *
 *                                                                         *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,        *
 *  EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF     *
 *  MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. *
 *  IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR      *
 *  OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,  *
 *  ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR  *
 *  OTHER DEALINGS IN THE SOFTWARE.                                        *
 ***************************************************************************/
package edu.brown.benchmark.tpceb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltTableRow;
import org.voltdb.VoltType;
import org.voltdb.types.TimestampType;

import edu.brown.benchmark.tpceb.TPCEConstants;

/**
 * Trade-Order Transaction <br/>
 * TPC-E Section 3.3.1
 * 
 * H-Store exceptions:
 *   1) getHoldingAssets cannot return SUM(HS_QTY * LT_PRICE), but only HS_QTY * LT_PRICE; so we do the SUM() manually in the frame
 *   2) getTaxrate is modified from the specification to use a join instead of a "in" (sub-queries are not supported in H-Store)
 *   3) send_to_market is quirky: we piggyback it with the result back to the client
 *   4) trade_id is not generated here because it is hard to coordinate its uniqueness; it is generated by the client instead and passed here
 *      as a parameter; it is returned, though, with the result
 *   5) Some values retrieved from tables are not used, but required to be passed between frames. Those are "unused values" as in Java.
 *   
 */
public class TradeOrder extends VoltProcedure {
    private final VoltTable trade_order_ret_template = new VoltTable(
            new VoltTable.ColumnInfo("buy_value", VoltType.FLOAT),
            new VoltTable.ColumnInfo("sell_value", VoltType.FLOAT),
            new VoltTable.ColumnInfo("tax_amount", VoltType.FLOAT),
            new VoltTable.ColumnInfo("trade_id", VoltType.BIGINT),
            new VoltTable.ColumnInfo("eAction", VoltType.INTEGER)
    );
    

    public final SQLStmt getCustomerAccount = new SQLStmt("select CA_B_ID, C_ID, C_TIER, CA_BAL from CUSTOMER_INFO where CA_ID = ?");

    public final SQLStmt getSecurity2 = new SQLStmt("select S_NAME, S_SYMB, S_DIVIDEND, S_YIELD from SECURITY where S_SYMB = ?");

    public final SQLStmt getLastTrade = new SQLStmt("select LT_PRICE from LAST_TRADE where LT_S_SYMB = ?");

    public final SQLStmt getTradeType = new SQLStmt("select TT_IS_MRKT, TT_IS_SELL from TRADE_TYPE where TT_ID = ?");

    public final SQLStmt getHoldingSummmary = new SQLStmt("select HS_QTY from HOLDING_SUMMARY where HS_CA_ID = ? and HS_S_SYMB = ?");

    public final SQLStmt getHoldingDesc = new SQLStmt("select H_QTY, H_PRICE from HOLDING where H_CA_ID = ? and H_S_SYMB = ? order by H_DTS desc");

    public final SQLStmt getHoldingAsc = new SQLStmt("select H_QTY, H_PRICE from HOLDING where H_CA_ID = ? and H_S_SYMB = ? order by H_DTS asc");
  
    public final SQLStmt getHoldingAssets = new SQLStmt(
            "select HS_QTY * LT_PRICE from HOLDING_SUMMARY, LAST_TRADE where HS_CA_ID = ? and LT_S_SYMB = HS_S_SYMB");

    public final SQLStmt insertTrade = new SQLStmt("insert into TRADE(T_ID, T_DTS, T_ST_ID, T_TT_ID, T_IS_CASH, T_S_SYMB, T_QTY, "
            + "T_BID_PRICE, T_CA_ID, T_TRADE_PRICE, T_CHRG, T_COMM, T_TAX, T_LIFO) " + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

    public final SQLStmt insertTradeRequest = new SQLStmt("insert into TRADE_REQUEST (TR_T_ID, TR_TT_ID, TR_S_SYMB, TR_QTY, TR_BID_PRICE, TR_CA_ID) " + "values (?, ?, ?, ?, ?, ?)");

    public final SQLStmt insertTradeHistory = new SQLStmt("insert into TRADE_HISTORY(TH_T_ID, TH_DTS, TH_ST_ID) values (?, ?, ?)");

    public VoltTable[] run(double requested_price, long acct_id, long is_lifo, long roll_it_back, long trade_qty, long type_is_margin, 
             String st_pending_id, String st_submitted_id, String symbol, String trade_type_id, long trade_id) throws VoltAbortException {
        
     /*   // frame 1: account info
      long tempAcc = 43000021007;
        voltQueueSQL(getCustomerAccount, acct_id);
        VoltTable acc_info = voltExecuteSQL()[0];
        assert acc_info.getRowCount() == 1;
        
        VoltTableRow acc_info_row = acc_info.fetchRow(0);
     //   long broker_id = acc_info_row.getLong("CA_B_ID");
     //   long cust_id = acc_info_row.getLong("C_ID");
 
//        int cust_tier = (int)acc_info_row.getLong("C_TIER");
        System.out.println("account id:" + acct_id);*/
      //  System.out.println("tier" + acct_id);
        // frame 3: estimating overall financial impact

        String s_name;
        

            voltQueueSQL(getSecurity2, symbol);
            VoltTable sec = voltExecuteSQL()[0];
  
            assert sec.getRowCount() == 1;
            VoltTableRow sec_row = sec.fetchRow(0);

            s_name = sec_row.getString("S_NAME");
          /*  long s_div = sec_row.getLong("S_DIVIDEND");
            long s_yield = sec_row.getLong("S_YIELD");
            System.out.println("Account ID in query" + acct_id);*/
            System.out.println("Symbol:" + symbol);
           // System.out.println("name" + s_name);
           // System.out.println("Dividend: " + s_div );
           // System.out.println("Yield: " + s_yield);
            
        voltQueueSQL(getLastTrade, symbol);
        voltQueueSQL(getTradeType, trade_type_id);
        voltQueueSQL(getHoldingSummmary, acct_id, symbol);
        VoltTable[] res = voltExecuteSQL();
        
        assert res[0].getRowCount() == 1;
        assert res[1].getRowCount() == 1;
        
      double market_price = res[0].fetchRow(0).getDouble("LT_PRICE");
        
        VoltTableRow tt_row = res[1].fetchRow(0);
        int type_is_market = (int)tt_row.getLong("TT_IS_MRKT");
        
        int type_is_sell = (int)tt_row.getLong("TT_IS_SELL");
        
        if (type_is_market == 1) {
            requested_price = market_price;
        }
        
        int hs_qty = 0;
        if (res[2].getRowCount() == 1) {
            hs_qty = (int)res[2].fetchRow(0).getLong("HS_QTY");
        }
        
        double buy_value = 0;
        double sell_value = 0;
        long needed_qty = trade_qty;
        
        // estimate impact on short and long positions
        if (type_is_sell == 1) {
            if (hs_qty > 0) {
                if (is_lifo == 1) {
                    voltQueueSQL(getHoldingDesc, acct_id, symbol);
                }
                else {
                    voltQueueSQL(getHoldingAsc, acct_id, symbol);
                }
                
                VoltTable hold_list = voltExecuteSQL()[0];
                
                for (int i = 0; i < hold_list.getRowCount() && needed_qty != 0; i++) {
                    VoltTableRow hold = hold_list.fetchRow(i);
                    int hold_qty = (int)hold.getLong("H_QTY");
                    //double hold_price = hold.getDouble("H_PRICE");
                    double hold_price =10;
                    if (hold_qty > needed_qty) {
                        buy_value += needed_qty * hold_price;
                        sell_value += needed_qty * requested_price;
                        needed_qty = 0;
                    }
                    else {
                        buy_value += hold_qty * hold_price;
                        sell_value += hold_qty * requested_price;
                        needed_qty = needed_qty - hold_qty;
                    }
                }
            }
        }
        else { // buy transaction
            if (hs_qty < 0) {
                if (is_lifo == 1) {
                    voltQueueSQL(getHoldingDesc, acct_id, symbol);
                }
                else {
                    voltQueueSQL(getHoldingAsc, acct_id, symbol);
                }
                
                VoltTable hold_list = voltExecuteSQL()[0];
                
                for (int i = 0; i < hold_list.getRowCount() && needed_qty != 0; i++) {
                    VoltTableRow hold = hold_list.fetchRow(i);
                    int hold_qty = (int)hold.getLong("H_QTY");
                  //  double hold_price = hold.getDouble("H_PRICE");
                  double hold_price = 10;  
                    if (hold_qty + needed_qty < 0) {
                        sell_value += needed_qty * hold_price;
                        buy_value += needed_qty * requested_price;
                        needed_qty = 0;
                    }
                    else {
                        hold_qty = -hold_qty;
                        sell_value += hold_qty * hold_price;
                        buy_value += hold_qty * requested_price;
                        needed_qty = needed_qty - hold_qty;
                    }
                }
            }
        }
  
        
        // trade status
        String status_id = (type_is_market == 1) ? st_submitted_id : st_pending_id;
        
        // frame 4: inserting the trade
        double comm_amount =10;
       
        int is_cash = (type_is_margin == 1) ? 0 : 1;
        TimestampType now_dts = new TimestampType();
        
        double charge_amount = 10;
        voltQueueSQL(insertTrade, trade_id, now_dts, status_id, trade_type_id, is_cash,
                symbol, trade_qty, requested_price, acct_id, null, charge_amount,
                comm_amount, 0, is_lifo);
        
        if (type_is_market == 0) {
            voltQueueSQL(insertTradeRequest, trade_id, trade_type_id, symbol, trade_qty,
                    requested_price, acct_id);
        }
        
        voltQueueSQL(insertTradeHistory, trade_id, now_dts, status_id);
        
        voltExecuteSQL();
        
        // frame 5: intentional roll-back
        if (roll_it_back == 1) {
            System.out.println("Intentional Rollback");
            throw new VoltAbortException("Intentional roll-back of a Trade-Order");
            
        }
        
       
        
        // frame 6: commit (nothing to do) and send_to_market, which is returned with the result
        int eAction = (type_is_market == 1) ? TPCEConstants.eMEEProcessOrder : TPCEConstants.eMEESetLimitOrderTrigger;
        double tax_amount = 10;
        VoltTable ret_values = trade_order_ret_template.clone(128);
        ret_values.addRow(buy_value, sell_value, tax_amount, trade_id, eAction);
        return new VoltTable[] {ret_values};
    }
}


