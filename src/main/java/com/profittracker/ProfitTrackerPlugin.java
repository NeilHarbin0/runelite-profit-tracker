package com.profittracker;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;

import net.runelite.api.events.*;

import net.runelite.api.widgets.WidgetID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.api.events.VarbitChanged;

import java.util.Arrays;

@Slf4j
@PluginDescriptor(
        name = "Profit Tracker"
)
public class ProfitTrackerPlugin extends Plugin
{
    ProfitTrackerGoldDrops goldDropsObject;
    ProfitTrackerInventoryValue inventoryValueObject;

    // the profit will be calculated against this value
    private long prevInventoryValue;
    private long totalProfit;

    private long startTickMillis;

    private boolean skipTickForProfitCalculation;
    private boolean inventoryValueChanged;
    private boolean inProfitTrackSession;
    private boolean runePouchContentsChanged;
    //Remembers if the bank was open last tick, because tick perfect bank close reports changes late
    private boolean bankJustClosed;
    private int[] RUNE_POUCH_VARBITS = {
            Varbits.RUNE_POUCH_AMOUNT1,
            Varbits.RUNE_POUCH_AMOUNT2,
            Varbits.RUNE_POUCH_AMOUNT3,
            Varbits.RUNE_POUCH_AMOUNT4,
            Varbits.RUNE_POUCH_RUNE1,
            Varbits.RUNE_POUCH_RUNE2,
            Varbits.RUNE_POUCH_RUNE3,
            Varbits.RUNE_POUCH_RUNE4
    };

    @Inject
    private Client client;

    @Inject
    private ProfitTrackerConfig config;

    @Inject
    private ItemManager itemManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ProfitTrackerOverlay overlay;

    @Override
    protected void startUp() throws Exception
    {
        // Add the inventory overlay
        overlayManager.add(overlay);

        goldDropsObject = new ProfitTrackerGoldDrops(client, itemManager);

        inventoryValueObject = new ProfitTrackerInventoryValue(client, itemManager);

        initializeVariables();

        // start tracking only if plugin was re-started mid game
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            startProfitTrackingSession();
        }

    }

    private void initializeVariables()
    {
        // value here doesn't matter, will be overwritten
        prevInventoryValue = -1;

        // profit begins at 0 of course
        totalProfit = 0;

        // this will be filled with actual information in startProfitTrackingSession
        startTickMillis = 0;

        // skip profit calculation for first tick, to initialize first inventory value
        skipTickForProfitCalculation = true;

        inventoryValueChanged = false;

        inProfitTrackSession = false;

        runePouchContentsChanged = false;

    }

    private void startProfitTrackingSession()
    {
        /*
        Start tracking profit from now on
         */

        initializeVariables();

        // initialize timer
        startTickMillis = System.currentTimeMillis();

        overlay.updateStartTimeMillies(startTickMillis);

        overlay.startSession();

        inProfitTrackSession = true;
    }

    @Override
    protected void shutDown() throws Exception
    {
        // Remove the inventory overlay
        overlayManager.remove(overlay);

    }

    @Subscribe
    public void onGameTick(GameTick gameTick)
    {
        /*
        Main plugin logic here

        1. If inventory changed,
            - calculate profit (inventory value difference)
            - generate gold drop (nice animation for showing gold earn or loss)

        2. Calculate profit rate and update in overlay

        */

        long tickProfit;

        if (!inProfitTrackSession)
        {
            return;
        }

        boolean skipOnce = false;
        if (bankJustClosed) {
            // Interacting with bank
            // itemContainerChanged does not report bank change if closed on same tick
            skipOnce = true;
        }
        bankJustClosed = false;

        if (inventoryValueChanged || runePouchContentsChanged)
        {
            if (skipOnce) {
                skipTickForProfitCalculation = true;
            }
            tickProfit = calculateTickProfit();

            // accumulate profit
            totalProfit += tickProfit;

            overlay.updateProfitValue(totalProfit);

            // generate gold drop
            if (config.goldDrops() && tickProfit != 0)
            {
                goldDropsObject.requestGoldDrop(tickProfit);
            }

            inventoryValueChanged = false;
            runePouchContentsChanged = false;
        }

    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        //Catch bank closing, as tick perfect close can cause onItemContainerChanged to not think it is in the bank
        if (event.getGroupId() == WidgetID.BANK_GROUP_ID || event.getGroupId() == WidgetID.BANK_INVENTORY_GROUP_ID) {
            bankJustClosed = true;
        }
    }


    private long calculateTickProfit()
    {
        /*
        Calculate and return the profit for this tick
        if skipTickForProfitCalculation is set, meaning this tick was bank / deposit
        so return 0

         */
        long newInventoryValue;
        long newProfit;

        // calculate current inventory value
        newInventoryValue = inventoryValueObject.calculateInventoryAndEquipmentValue();

        if (!skipTickForProfitCalculation)
        {
            // calculate new profit
            newProfit = newInventoryValue - prevInventoryValue;

        }
        else
        {
            /* first time calculation / banking / equipping */
            log.debug("Skipping profit calculation!");

            skipTickForProfitCalculation = false;

            // no profit this tick
            newProfit = 0;
        }

        // update prevInventoryValue for future calculations anyway!
        prevInventoryValue = newInventoryValue;

        return newProfit;
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        /*
        this event tells us when inventory has changed
        and when banking/equipment event occured this tick
         */
        log.debug("onItemContainerChanged container id: " + event.getContainerId());

        int containerId = event.getContainerId();

        if( containerId == InventoryID.INVENTORY.getId() ||
            containerId == InventoryID.EQUIPMENT.getId()) {
            // inventory has changed - need calculate profit in onGameTick
            inventoryValueChanged = true;

        }

        // in these events, inventory WILL be changed but we DON'T want to calculate profit!
        if(     containerId == InventoryID.BANK.getId()) {
            // this is a bank interaction.
            // Don't take this into account
            skipTickForProfitCalculation = true;

        }

    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        runePouchContentsChanged = Arrays.stream(RUNE_POUCH_VARBITS).anyMatch(vb -> event.getVarbitId() == vb);
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        /* for ignoring deposit in deposit box */
        log.debug(String.format("Click! ID: %d ,menuOption: %s, menuTarget: %s",
                  event.getId(), event.getMenuOption(), event.getMenuTarget()));
        String menuOption = event.getMenuOption();
        switch (event.getId()){
            case ObjectID.BANK_DEPOSIT_BOX:
            case ObjectID.DEPOSIT_POOL:
                // we've interacted with a deposit box/pool. Don't take this tick into account for profit calculation
                skipTickForProfitCalculation = true;
        }

        if (event.getId() == 1 && (menuOption.startsWith("Withdraw-") || menuOption.startsWith("Deposit-"))){
            // Interacting with bank, because itemContainerChanged does not report bank change if closed on same tick
            skipTickForProfitCalculation = true;
        }
        if (event.getId() == ObjectID.BANK_DEPOSIT_BOX || event.getId() == ObjectID.DEPOSIT_POOL || event.getId() == ObjectID.BANK_DEPOSIT_CHEST) {
            // we've interacted with a deposit box/pool. Don't take this tick into account for profit calculation
            skipTickForProfitCalculation = true;
        }

        switch (event.getItemId()) {
            case ItemID.PLANK_SACK:
            case ItemID.FISH_SACK_BARREL:
            case ItemID.FISH_BARREL:
            case ItemID.COAL_BAG:
            case ItemID.COLOSSAL_POUCH:
            case ItemID.LARGE_POUCH:
            case ItemID.MEDIUM_POUCH:
            case ItemID.SMALL_POUCH:
            case ItemID.FORESTRY_KIT:
            case ItemID.FORESTRY_BASKET:
                if (menuOption.equalsIgnoreCase("empty") || menuOption.equalsIgnoreCase("fill")){
                    // Ignore manual changes to container items as the items have not been lost
                    skipTickForProfitCalculation = true;
                }
        }
    }

    @Provides
    ProfitTrackerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ProfitTrackerConfig.class);
    }


    @Subscribe
    public void onScriptPreFired(ScriptPreFired scriptPreFired)
    {
        goldDropsObject.onScriptPreFired(scriptPreFired);
    }
}
