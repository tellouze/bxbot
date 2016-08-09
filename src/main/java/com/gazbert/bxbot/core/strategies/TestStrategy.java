/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Gareth Jon Lynch
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.gazbert.bxbot.core.strategies;

import com.gazbert.bxbot.core.api.strategy.StrategyConfig;
import com.gazbert.bxbot.core.api.strategy.StrategyException;
import com.gazbert.bxbot.core.api.strategy.TradingStrategy;
import com.gazbert.bxbot.core.api.trading.*;
import org.apache.logging.log4j.LogManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Test strategy for holding up the bot - no orders are sent to the exchange.
 * <p>
 * Only the public Trading API operations are invoked.
 * <p>
 * Essentially, a non-transactional copy of the {@link ExampleScalpingStrategy}.
 * <p>
 * It was written for testing (potential) changes to the bot with a live exchange.
 * </p>
 *
 * @author gazbert
 * @since 20/07/2016
 */
public class TestStrategy implements TradingStrategy {

    private static final org.apache.logging.log4j.Logger LOG = LogManager.getLogger();

    /**
     * Reference to the main Trading API.
     */
    private TradingApi tradingApi;

    /**
     * The market this strategy is trading on.
     */
    private Market market;

    /**
     * The state of the order.
     */
    private OrderState lastOrder;

    /**
     * BTC buy order amount. This was loaded from the strategy entry in the ./config/strategies.xml config file.
     */
    private BigDecimal btcBuyOrderAmount;


    /**
     * Initialises the Trading Strategy.
     * Called once by the Trading Engine when the bot starts up; it's a bit like a servlet init() method.
     *
     * @param tradingApi the Trading API. Use this to make trades and stuff.
     * @param market     the market for this strategy. This is the market the strategy is currently running on - you wire
     *                   this up in the markets.xml and strategies.xml files.
     * @param config     optional configuration for the strategy. Contains any (optional) config you setup in the
     *                   strategies.xml file.
     */
    @Override
    public void init(TradingApi tradingApi, Market market, StrategyConfig config) {

        LOG.info(() -> "Initialising Trading Strategy...");

        this.tradingApi = tradingApi;
        this.market = market;
        getConfigForStrategy(config);

        LOG.info(() -> "Trading Strategy initialised successfully!");
    }

    /**
     * <p>
     * This is the main execution method of the Trading Strategy. It is where your algorithm lives.
     * </p>
     * <p>
     * <p>
     * It is called by the Trading Engine during each trade cycle, e.g. every 60s. The trade cycle is configured in
     * the .config/engine.xml file.
     * </p>
     *
     * @throws StrategyException if something unexpected occurs. This tells the Trading Engine to shutdown the bot
     *                           immediately to help prevent unexpected losses.
     */
    @Override
    public void execute() throws StrategyException {

        LOG.info(() -> market.getName() + " Checking order status...");

        try {
            // Grab the latest order book for the market.
            final MarketOrderBook orderBook = tradingApi.getMarketOrders(market.getId());

            final List<MarketOrder> buyOrders = orderBook.getBuyOrders();
            if (buyOrders.size() == 0) {
                LOG.warn("Exchange returned empty Buy Orders. Ignoring this trade window. OrderBook: " + orderBook);
                return;
            }

            final List<MarketOrder> sellOrders = orderBook.getSellOrders();
            if (sellOrders.size() == 0) {
                LOG.warn("Exchange returned empty Sell Orders. Ignoring this trade window. OrderBook: " + orderBook);
                return;
            }

            // Get the current BID and ASK spot prices.
            final BigDecimal currentBidPrice = buyOrders.get(0).getPrice();
            final BigDecimal currentAskPrice = sellOrders.get(0).getPrice();

            LOG.info(() -> market.getName() + " Current BID price=" +
                    new DecimalFormat("#.########").format(currentBidPrice));
            LOG.info(() -> market.getName() + " Current ASK price=" +
                    new DecimalFormat("#.########").format(currentAskPrice));

            /*
             * Is this the first time the Strategy has been called? If yes, we initialise the OrderState so we can keep
             * track of orders during later trace cycles.
             */
            if (lastOrder == null) {
                LOG.info(() -> market.getName() +
                        " First time Strategy has been called - creating new OrderState object.");
                lastOrder = new OrderState();
            }

            // Always handy to log what the last order was during each trace cycle.
            LOG.info(() -> market.getName() + " Last Order was: " + lastOrder);

            /*
             * Execute the appropriate algorithm based on the last order type.
             */
            if (lastOrder.type == OrderType.BUY) {
                executeAlgoForWhenLastOrderWasBuy();

            } else if (lastOrder.type == OrderType.SELL) {
                executeAlgoForWhenLastOrderWasSell(currentBidPrice, currentAskPrice);

            } else if (lastOrder.type == null) {
                executeAlgoForWhenLastOrderWasNone(currentBidPrice);
            }

        } catch (ExchangeNetworkException e) {

            // Your timeout handling code could go here.
            // We are just going to log it and swallow it, and wait for next trade cycle.
            LOG.error(market.getName() + " Failed to get market orders because Exchange threw network exception. " +
                    "Waiting until next trade cycle.", e);

        } catch (TradingApiException e) {

            // Your error handling code could go here...
            // We are just going to re-throw as StrategyException for engine to deal with - it will shutdown the bot.
            LOG.error(market.getName() + " Failed to get market orders because Exchange threw TradingApi exception. " +
                    " Telling Trading Engine to shutdown bot!", e);
            throw new StrategyException(e);
        }
    }

    /**
     * Algo for executing when last order we none. This is called when the Trading Strategy is invoked for the first time.
     * We start off with a buy order at current BID price.
     *
     * @param currentBidPrice the current market BID price.
     * @throws StrategyException if an unexpected exception is received from the Exchange Adapter.
     *                           Throwing this exception indicates we want the Trading Engine to shutdown the bot.
     */
    private void executeAlgoForWhenLastOrderWasNone(BigDecimal currentBidPrice) throws StrategyException {

        LOG.info(() -> market.getName() + " OrderType is NONE - placing new BUY order at ["
                + new DecimalFormat("#.########").format(currentBidPrice) + "]");

        try {

            // Calculate the amount of altcoin to buy for given amount of BTC.
            final BigDecimal amountOfAltcoinToBuyForGivenBtc = getAmountOfAltcoinToBuyForGivenBtcAmount(btcBuyOrderAmount);

            // Send the order to the exchange
            LOG.info(() -> market.getName() + " Sending initial BUY order to exchange --->");

            //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
            // lastOrder.id = tradingApi.createOrder(market.getId(), OrderType.BUY, amountOfAltcoinToBuyForGivenBtc, currentBidPrice);
            //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

            LOG.info(() -> market.getName() + " Initial BUY Order sent successfully. ID: " + lastOrder.id);

            // update last order details
            lastOrder.price = currentBidPrice;
            lastOrder.type = OrderType.BUY;
            lastOrder.amount = amountOfAltcoinToBuyForGivenBtc;

        } catch (ExchangeNetworkException e) {

            // Your timeout handling code could go here, e.g. you might want to check if the order actually
            // made it to the exchange? And if not, resend it...
            // We are just going to log it and swallow it, and wait for next trade cycle.
            LOG.error(market.getName() + " Initial order to BUY altcoin failed because Exchange threw network exception. " +
                    "Waiting until next trade cycle.", e);

        } catch (TradingApiException e) {

            // Your error handling code could go here...
            // We are just going to re-throw as StrategyException for engine to deal with - it will shutdown the bot.
            LOG.error(market.getName() + " Initial order to BUY altcoin failed because Exchange threw TradingApi exception. " +
                    " Telling Trading Engine to shutdown bot!", e);
            throw new StrategyException(e);
        }
    }

    /**
     * <p>
     * Algo for executing when last order we placed on the exchanges was a BUY.
     * </p>
     * <p>
     * <p>
     * If last buy order filled, we try and sell at a profit.
     * </p>
     *
     * @throws StrategyException if an unexpected exception is received from the Exchange Adapter.
     *                           Throwing this exception indicates we want the Trading Engine to shutdown the bot.
     */
    private void executeAlgoForWhenLastOrderWasBuy() throws StrategyException {

        try {

            // Fetch our current open orders and see if the buy order is still outstanding/open on the exchange

            /////////////////////////////////////////////////////////////////////////////////
            // final List<OpenOrder> myOrders = tradingApi.getYourOpenOrders(market.getId());
            /////////////////////////////////////////////////////////////////////////////////

            final List<OpenOrder> myOrders = new ArrayList<>();
            boolean lastOrderFound = false;
            for (final OpenOrder myOrder : myOrders) {
                if (myOrder.getId().equals(lastOrder.id)) {
                    lastOrderFound = true;
                    break;
                }
            }

            // If the order is not there, it must have all filled.
            if (!lastOrderFound) {

                LOG.info(() -> market.getName() +
                        " ^^^ Yay!!! Last BUY Order Id [" + lastOrder.id + "] filled at [" + lastOrder.price + "]");

                /*
                 * The last buy order was filled, so lets see if we can send a new sell order...
                 *
                 * IMPORTANT - new sell order ASK price must be > (last order price + exchange fees) because:
                 *
                 * 1. if we put sell amount in as same amount as previous buy, the exchange barfs because we don't have
                 *    enough units to cover the transaction fee.
                 * 2. we could end up selling at a loss.
                 *
                 * For this example strategy, we're just going to add 1% on top of original bid price (last order)
                 * and combine the exchange buy and sell fees. Your algo will have other ideas on how much profit to make ;-)
                 */
                final BigDecimal percentProfitToMake = new BigDecimal("0.01");
                LOG.info(() -> market.getName() +
                        " Percentage profit to make on sell order is: " + percentProfitToMake);

                final BigDecimal buyOrderPercentageFee = tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
                LOG.info(() -> market.getName() + " Exchange fee in percent for buy order is: " + buyOrderPercentageFee);

                final BigDecimal sellOrderPercentageFee = tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());
                LOG.info(() -> market.getName() + " Exchange fee in percent for sell order is: " + sellOrderPercentageFee);

                final BigDecimal totalPercentageIncrease = percentProfitToMake.add(buyOrderPercentageFee).add(sellOrderPercentageFee);
                LOG.info(() -> market.getName() + " Total percentage increase for new sell order is: " + totalPercentageIncrease);

                final BigDecimal amountToAdd = lastOrder.price.multiply(totalPercentageIncrease);
                LOG.info(() -> market.getName() + " Amount to add last order price: " + amountToAdd);

                /*
                 * Most exchanges (if not all) use 8 decimal places.
                 * It's usually best to round up the ASK price in your calculations to maximise gains.
                 */
                final BigDecimal newAskPrice = lastOrder.price.add(amountToAdd).setScale(8, RoundingMode.HALF_UP);
                LOG.info(() -> market.getName() + " Placing new SELL order at ask price [" +
                        new DecimalFormat("#.########").format(newAskPrice) + "]");

                LOG.info(() -> market.getName() + " Sending new SELL order to exchange --->");

                // Build the new sell order

                ////////////////////////////////////////////////////////////////////////////////////////////////////////
                // lastOrder.id = tradingApi.createOrder(market.getId(), OrderType.SELL, lastOrder.amount, newAskPrice);
                ////////////////////////////////////////////////////////////////////////////////////////////////////////

                LOG.info(() -> market.getName() + " New SELL Order sent successfully. ID: " + lastOrder.id);

                // update last order state
                lastOrder.price = newAskPrice;
                lastOrder.type = OrderType.SELL;
            } else {

                /*
                 * BUY order has not filled yet.
                 * Could be nobody has jumped on it yet... the order is only part filled... or market has gone up and
                 * we've been outbid and have a stuck buy order, in which case we have, to wait for the market to fall...
                 * unless you tweak this code to cancel the current order and raise your bid - remember to deal with any
                 * part-filled orders!
                 */
                LOG.info(() -> market.getName() + " !!! Still have BUY Order " + lastOrder.id
                        + " waiting to fill at [" + lastOrder.price + "] - holding last BUY order...");
            }

        } catch (ExchangeNetworkException e) {

            // Your timeout handling code could go here, e.g. you might want to check if the order actually
            // made it to the exchange? And if not, resend it...
            // We are just going to log it and swallow it, and wait for next trade cycle.
            LOG.error(market.getName() + " New Order to SELL altcoin failed because Exchange threw network exception. " +
                    "Waiting until next trade cycle. Last Order: " + lastOrder, e);

        } catch (TradingApiException e) {

            // Your error handling code could go here...
            // We are just going to re-throw as StrategyException for engine to deal with - it will shutdown the bot.
            LOG.error(market.getName() + " New order to SELL altcoin failed because Exchange threw TradingApi exception. " +
                    " Telling Trading Engine to shutdown bot! Last Order: " + lastOrder, e);
            throw new StrategyException(e);
        }
    }

    /**
     * <p>
     * Algo for executing when last order we placed on the exchange was a SELL.
     * </p>
     * <p>
     * <p>
     * If last sell order filled, we send a new buy order to the exchange.
     * </p>
     *
     * @param currentBidPrice the current market BID price.
     * @param currentAskPrice the current market ASK price.
     * @throws StrategyException if an unexpected exception is received from the Exchange Adapter.
     *                           Throwing this exception indicates we want the Trading Engine to shutdown the bot.
     */
    private void executeAlgoForWhenLastOrderWasSell(BigDecimal currentBidPrice, BigDecimal currentAskPrice)
            throws StrategyException {

        try {

            // Fetch our current open orders and see if the sell order is still outstanding/unfilled on the exchange

            /////////////////////////////////////////////////////////////////////////////////
            // final List<OpenOrder> myOrders = tradingApi.getYourOpenOrders(market.getId());
            /////////////////////////////////////////////////////////////////////////////////

            final List<OpenOrder> myOrders = new ArrayList<>();
            boolean lastOrderFound = false;
            for (final OpenOrder myOrder : myOrders) {
                if (myOrder.getId().equals(lastOrder.id)) {
                    lastOrderFound = true;
                    break;
                }
            }

            // if the order is not there, it must have all filled.
            if (!lastOrderFound) {

                LOG.info(() -> market.getName() +
                        " ^^^ Yay!!! Last SELL Order Id [" + lastOrder.id + "] filled at [" + lastOrder.price + "]");

                // Get amount of altcoin we can buy for given BTC amount.
                final BigDecimal amountOfAltcoinToBuyForGivenBtc = getAmountOfAltcoinToBuyForGivenBtcAmount(btcBuyOrderAmount);
                LOG.info(() -> market.getName() + " Placing new BUY order at bid price [" +
                        new DecimalFormat("#.########").format(currentBidPrice) + "]");

                LOG.info(() -> market.getName() + " Sending new BUY order to exchange --->");

                // Send the buy order to the exchange.

                //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
                // lastOrder.id = tradingApi.createOrder(market.getId(), OrderType.BUY, amountOfAltcoinToBuyForGivenBtc, currentBidPrice);
                //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

                LOG.info(() -> market.getName() + " New BUY Order sent successfully. ID: " + lastOrder.id);

                // update last order details
                lastOrder.price = currentBidPrice;
                lastOrder.type = OrderType.BUY;
                lastOrder.amount = amountOfAltcoinToBuyForGivenBtc;
            } else {

                /*
                 * SELL order not filled yet.
                 * Could be nobody has jumped on it yet... it is only part filled... or market has gone down and we've
                 * been undercut and have a stuck sell order, in which case we have to wait for market to recover...
                 * unless you tweak this code to cancel the current order and lower your ask - remember to deal with any
                 * part-filled orders!
                 */
                if (currentAskPrice.compareTo(lastOrder.price) < 0) {
                    LOG.info(() -> market.getName() + " <<< Current ask price [" + currentAskPrice
                            + "] is LOWER then last order price ["
                            + lastOrder.price + "] - holding last SELL order...");

                } else if (currentAskPrice.compareTo(lastOrder.price) > 0) {
                    // TODO throw illegal state exception
                    LOG.error(market.getName() + " >>> Current ask price [" + currentAskPrice
                            + "] is HIGHER than last order price ["
                            + lastOrder.price + "] - IMPOSSIBLE! BX-bot must have sold?????");

                } else if (currentAskPrice.compareTo(lastOrder.price) == 0) {
                    LOG.info(() -> market.getName() + " === Current ask price [" + currentAskPrice
                            + "] is EQUAL to last order price ["
                            + lastOrder.price + "] - holding last SELL order...");
                }
            }
        } catch (ExchangeNetworkException e) {

            // Your timeout handling code could go here, e.g. you might want to check if the order actually
            // made it to the exchange? And if not, resend it...
            // We are just going to log it and swallow it, and wait for next trade cycle.
            LOG.error(market.getName() + " New Order to BUY altcoin failed because Exchange threw network exception. " +
                    "Waiting until next trade cycle. Last Order: " + lastOrder, e);

        } catch (TradingApiException e) {

            // Your error handling code could go here...
            // We are just going to re-throw as StrategyException for engine to deal with - it will shutdown the bot.
            LOG.error(market.getName() + " New order to BUY altcoin failed because Exchange threw TradingApi exception. " +
                    " Telling Trading Engine to shutdown bot! Last Order: " + lastOrder, e);
            throw new StrategyException(e);
        }
    }

    /**
     * Returns amount of altcoin to buy for a given amount of BTC based on last market trade price.
     *
     * @param amountOfBtcToTrade the amount of BTC we have to trade (buy) with.
     * @return the amount of altcoin we can buy for the given BTC amount.
     * @throws TradingApiException      if an unexpected error occurred contacting the exchange.
     * @throws ExchangeNetworkException if a request to the exchange has timed out.
     */
    private BigDecimal getAmountOfAltcoinToBuyForGivenBtcAmount(BigDecimal amountOfBtcToTrade) throws
            TradingApiException, ExchangeNetworkException {

        LOG.info(() -> market.getName() + " Calculating amount of altcoin to buy for " +
                new DecimalFormat("#.########").format(amountOfBtcToTrade) + " BTC");

        // Fetch the last trade price
        final BigDecimal lastTradePriceInBtcForOneAltcoin = tradingApi.getLatestMarketPrice(market.getId());
        LOG.info(() -> market.getName() + " Last trade price for 1 altcoin was: " +
                new DecimalFormat("#.########").format(lastTradePriceInBtcForOneAltcoin) + " BTC");

        /*
         * Most exchanges (if not all) use 8 decimal places and typically round in favour of the exchange.
         * It's usually safest to round down the order quantity in your calculations.
         */
        final BigDecimal amountOfAltcoinToBuyForGivenBtc = amountOfBtcToTrade.divide(
                lastTradePriceInBtcForOneAltcoin, 8, RoundingMode.HALF_DOWN);

        LOG.info(() -> market.getName() + " Amount of altcoin to BUY for [" +
                new DecimalFormat("#.########").format(amountOfBtcToTrade) +
                " BTC] based on last market trade price: " + amountOfAltcoinToBuyForGivenBtc);

        return amountOfAltcoinToBuyForGivenBtc;
    }

    /**
     * Loads the config for the strategy. We expect the 'btc-buy-order-amount' config item to be present in the
     * ./config/strategies.xml config file.
     *
     * @param config the config for the Trading Strategy.
     */
    private void getConfigForStrategy(StrategyConfig config) {

        final String btcBuyOrderAmountFromConfigAsString = config.getConfigItem("btc-buy-order-amount");
        if (btcBuyOrderAmountFromConfigAsString == null) {
            // game over - kill it off now.
            throw new IllegalArgumentException("Mandatory btc-buy-order-amount value missing in strategy.xml config.");
        }
        LOG.info(() -> "<btc-buy-order-amount> from config is: " + btcBuyOrderAmountFromConfigAsString);

        // will fail fast if value is not a number!
        btcBuyOrderAmount = new BigDecimal(btcBuyOrderAmountFromConfigAsString);
        LOG.info(() -> "btcBuyOrderAmount: " + btcBuyOrderAmount);
    }

    /**
     * <p>
     * Models the state of an Order we have placed on the exchange.
     * </p>
     * <p>
     * <p>
     * Typically, you would maintain order state in a database or use some other persistent datasource to recover from
     * restarts and for audit purposes. In this example, we are storing the state in memory to keep it simple.
     * </p>
     */
    private class OrderState {

        /**
         * Id - default to null.
         */
        private String id = null;

        /**
         * Type: buy/sell. We default to null which means no order has been placed yet, i.e. we've just started!
         */
        private OrderType type = null;

        /**
         * Price to buy/sell at - default to zero.
         */
        private BigDecimal price = BigDecimal.ZERO;

        /**
         * Number of units to buy/sell - default to zero.
         */
        private BigDecimal amount = BigDecimal.ZERO;

        @Override
        public String toString() {
            return OrderState.class.getSimpleName()
                    + " ["
                    + "id=" + id
                    + ", type=" + type
                    + ", price=" + price
                    + ", amount=" + amount
                    + "]";
        }
    }
}