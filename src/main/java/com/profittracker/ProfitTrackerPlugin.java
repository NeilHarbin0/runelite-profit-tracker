package com.profittracker;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;

import net.runelite.api.events.*;

import net.runelite.api.widgets.InterfaceID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.api.events.VarbitChanged;
import org.apache.commons.lang3.ArrayUtils;

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
    // Collection of items that was last used to calculate value, includes inventory and equipment
    private Item[] prevInventoryItems;
    private Item[] prevBankItems;
    private long totalProfit;

    private long startTickMillis;

    private boolean skipTickForProfitCalculation;
    private boolean inventoryValueChanged;
    private boolean bankValueChanged;
    private boolean inProfitTrackSession;
    private boolean runePouchContentsChanged;
    // Remembers if the bank was open last tick, because tick perfect bank close reports changes late
    private boolean bankJustClosed;
    // Set when using a deposit menu option. Used to create a depositing deficit for the next time you open bank
    // This ensures using a deposit box doesn't spam coin drops, but also doesn't get out of sync when the race
    // condition with menu options and container changes causes some anyways
    private boolean depositingItem;
    private int depositDeficit;
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
        prevInventoryItems = null;
        prevBankItems = null;

        // profit begins at 0 of course
        totalProfit = 0;

        // this will be filled with actual information in startProfitTrackingSession
        startTickMillis = 0;

        // skip profit calculation for first tick, to initialize first inventory value
        skipTickForProfitCalculation = true;

        inventoryValueChanged = false;

        bankValueChanged = false;

        inProfitTrackSession = false;

        runePouchContentsChanged = false;

        bankJustClosed = false;

        depositingItem = false;

        depositDeficit = 0;
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
    public void onGameTick(GameTick gameTick) throws Exception
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
            if (config.autoStart()){
                startUp();
                inventoryValueChanged = true;
            } else {
                return;
            }
        }

        boolean skipOnce = false;
        if (bankJustClosed) {
            // Interacting with bank
            // itemContainerChanged does not report bank change if closed on same tick
            skipOnce = true;
        }
        bankJustClosed = false;

        if (inventoryValueChanged || runePouchContentsChanged || bankValueChanged)
        {
            if (skipOnce) {
                skipTickForProfitCalculation = true;
            }
            tickProfit = calculateTickProfit();

            // accumulate profit
            if (depositingItem){
                // Track a deficit for deposits because of deposit box problems
                depositDeficit += tickProfit;
                depositingItem = false;
                tickProfit = 0;
            }

            // Resync with untracked changes from using deposit box
            if (bankValueChanged) {
                tickProfit += depositDeficit;
                depositDeficit = 0;
            }

            totalProfit += tickProfit;
            overlay.updateProfitValue(totalProfit);

            // generate gold drop
            if (config.goldDrops() && tickProfit != 0)
            {
                goldDropsObject.requestGoldDrop(tickProfit);
            }

            inventoryValueChanged = false;
            bankValueChanged = false;
            runePouchContentsChanged = false;
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        //Catch untracked storage closing, as tick perfect close can cause onItemContainerChanged to not see the change
        if (event.getGroupId() == 871 || //Huntsman's kit
            event.getGroupId() == InterfaceID.SEED_VAULT) { // Seed vault
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
        Item[] newInventoryItems;
        Item[] newBankItems;
        long newProfit;
        Item[] inventoryDifference;
        Item[] bankDifference = new Item[0];

        // calculate current inventory value
        //newInventoryValue = inventoryValueObject.calculateInventoryAndEquipmentValue();
        newInventoryItems = inventoryValueObject.getInventoryAndEquipmentContents();
        newBankItems = inventoryValueObject.getBankContents();

        if (!skipTickForProfitCalculation && prevInventoryItems != null)
        {
            // calculate new profit
            // newProfit = newInventoryValue - prevInventoryValue;
            inventoryDifference = inventoryValueObject.getItemCollectionDifference(prevInventoryItems,newInventoryItems);
            newProfit = inventoryValueObject.calculateItemValue(inventoryDifference);
            if (prevBankItems != null && newBankItems != null) {
                bankDifference = inventoryValueObject.getItemCollectionDifference(prevBankItems,newBankItems);
                // Profit is recalculated on all items instead of summed just in case item values could change between calculations
                Item[] inventoryAndBankDifference = ArrayUtils.addAll(inventoryDifference,bankDifference);
                newProfit = inventoryValueObject.calculateItemValue(inventoryAndBankDifference);
            }

            log.debug("Calculated " + newProfit + " profit for " + (inventoryDifference.length + bankDifference.length) + " item changes.");
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
        //prevInventoryValue = newInventoryValue;
        prevInventoryItems = newInventoryItems;
        if (newBankItems != null) {
            if (prevBankItems == null) {
                // If user hasn't opened bank yet, the deficit doesn't help us resync
                depositDeficit = 0;
            }
            prevBankItems = newBankItems;
        }

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

        if( containerId == InventoryID.BANK.getId()) {
            bankValueChanged = true;
        }

        // In these events, inventory WILL be changed, but we DON'T want to calculate profit!
        if( containerId == 855 || // Huntsman's kit
            containerId == InventoryID.SEED_VAULT.getId()) { // Seed vault
            skipTickForProfitCalculation = true;
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        if (Arrays.stream(RUNE_POUCH_VARBITS).anyMatch(vb -> event.getVarbitId() == vb)){
            runePouchContentsChanged = true;
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        /* for ignoring deposit in deposit box */
        log.debug(String.format("Click! ID: %d ,menuOption: %s, menuTarget: %s",
                  event.getId(), event.getMenuOption(), event.getMenuTarget()));
        String menuOption = event.getMenuOption();

        String containerMenuOptions[] = {"Deposit-"};
        for (int i = 0; i < containerMenuOptions.length; i++){
            if (menuOption.startsWith(containerMenuOptions[i])){
                // Backup catch for various bank interfaces to deposit items
                // Event object does not seem to provide information that would otherwise tell us it's a bank
                // Still, it is possible to have game tick happen before a container changes to reflect menu option,
                // which can cause unexpected profit/loss, particularly when clicking a lot in a deposit box.
                depositingItem = true;
                break;
            }
        }

        // Container items
        // Ignore profit changes for items that act as storage only
        switch (event.getItemId()) {
            case ItemID.COLOSSAL_POUCH: // Fill, empty | fill, empty | essence must be taken out to use
            case ItemID.GIANT_POUCH:
            case ItemID.LARGE_POUCH:
            case ItemID.MEDIUM_POUCH:
            case ItemID.SMALL_POUCH:

            case ItemID.HUNTSMANS_KIT: // Fill, Empty, view(custom storage interface)
            case ItemID.TACKLE_BOX: // View, ??
                switch (menuOption.toLowerCase()) {
                    // These items act as long term storage, and are more like banks
                    // Items are not used directly from them either
                    case "empty":
                    case "fill":
                    case "use":
                        log.debug("Ignoring storage item interaction.");
                        // Ignore manual changes to container items as the items have not been lost
                        skipTickForProfitCalculation = true;
                }
        }

        // Half-volatile storage
        // Items that can change from pure storage to volatile storage by opening
        switch (event.getItemId()) {
            case ItemID.FISH_SACK_BARREL:
            case ItemID.FISH_BARREL: //Fill, open | empty

            case ItemID.GEM_BAG_12020: //Fill, empty, open | empty
            case ItemID.HERB_SACK: // Fill, empty, open | empty

            case ItemID.SEED_BOX: // Open

            case ItemID.SMALL_MEAT_POUCH: // Fill, Empty
            case ItemID.LARGE_MEAT_POUCH: // Fill, Empty
            case ItemID.SMALL_FUR_POUCH: // Fill, Empty
            case ItemID.MEDIUM_FUR_POUCH: // Fill, Empty
            case ItemID.LARGE_FUR_POUCH: // Fill, Empty
                switch (menuOption.toLowerCase()) {
                    // These items act as long term storage, and are more like banks
                    // Items are not used directly from them either
                    case "empty":
                    case "fill":
                    case "use":
                        // Ignore manual changes to container items as the items have not been lost
                        skipTickForProfitCalculation = false;
                }
        }

        // If items can be used directly from the storage container, count the profit as otherwise we might not see
        // them get used, including interface only withdraws
        switch (event.getItemId()) {
            case ItemID.PLANK_SACK: // Fill, empty | use (dumps into inventory)
                // Items can be used directly from sack
                // Filling sack requires inventory as intermediate

            case ItemID.COAL_BAG: // Fill, empty, open | fill, empty
                //Coal can be directly used from the sack, and be filled directly from bank

                // case ItemID.BASKET: //Fill, remove-one, empty | fill | basket turns into different name like "Bananas(#)"
                //Empty sack
            case ItemID.BOLT_POUCH: //Open(remove interface) | | bolts can be worn via armor interface extra ammo slot
                //Rune pouch // Covered by withdraw interface

            case ItemID.FLAMTAER_BAG: // Fill, empty | empty (dumps into inventory) | items can be used directly from sack
            case ItemID.MASTER_SCROLL_BOOK: // Interface with remove option | items can be used from book via activate and teleport
            case ItemID.GNOMISH_FIRELIGHTER: // Check, uncharge | Firelighter charges used directly from box when burning logs
            case ItemID.STEEL_KEY_RING: //Add keys via use on ring | remove via remove interface

            case ItemID.LOG_BASKET: // Fill, Check(dialog based withdraw), Close/Open | Empty
            case ItemID.FORESTRY_KIT: // View(kit has withdraw interface), Fill | Use(dumps to bank) | rations used directly from kit
            case ItemID.FORESTRY_BASKET: // Fill, view(kit has withdraw interface/basket has none) | use (dumps to bank) | rations used directly from kit

            case ItemID.REAGENT_POUCH: // Fill, open, empty, use, check | Use (dumps to bank)
                switch (menuOption.toLowerCase()) {
                    // Interacting with these things pulls items from volatile unrecorded space
                    // Coal bag could be filled from bank to use in smithing, or filled from mining
                    // Price needs to be recorded while banking or otherwise
                    case "empty":
                    case "fill":
                    case "use":
                        // Ensure item containers
                        skipTickForProfitCalculation = false;
                }
        }

        // If items can be placed directly into the container, skipping inventory, track profit to not miss emptying
        switch (event.getItemId()) {
            case ItemID.OPEN_FISH_SACK_BARREL:
            case ItemID.OPEN_FISH_BARREL:

            //Coffins can only be emptied via configure, which doesn't report an ItemId we can see for emptying
            case ItemID.BRONZE_COFFIN: // Fill, configure, open
            case ItemID.BLACK_COFFIN:
            case ItemID.STEEL_COFFIN:
            case ItemID.SILVER_COFFIN:
            case ItemID.GOLD_COFFIN:

            case ItemID.OPEN_BRONZE_COFFIN:
            case ItemID.OPEN_BLACK_COFFIN:
            case ItemID.OPEN_STEEL_COFFIN:
            case ItemID.OPEN_SILVER_COFFIN:
            case ItemID.OPEN_GOLD_COFFIN: // Fill, configure, close

            case ItemID.OPEN_GEM_BAG: // Fill, Empty
            case ItemID.OPEN_COAL_BAG: // Fill, Empty

            case ItemID.OPEN_HERB_SACK:
            case ItemID.OPEN_SEED_BOX:

            case ItemID.OPEN_LOG_BASKET:
            case ItemID.OPEN_FORESTRY_BASKET:

            case ItemID.SMALL_MEAT_POUCH_OPEN: // Fill, Empty
            case ItemID.LARGE_MEAT_POUCH_OPEN: // Fill, Empty
            case ItemID.SMALL_FUR_POUCH_OPEN: // Fill, Empty
            case ItemID.MEDIUM_FUR_POUCH_OPEN: // Fill, Empty
            case ItemID.LARGE_FUR_POUCH_OPEN: // Fill, Empty

            case ItemID.LOOTING_BAG: // Open, Deposit(store interface) | view (deposit interface) | Commonly opened and closed

            case ItemID.OPEN_REAGENT_POUCH:
                switch (menuOption.toLowerCase()) {
                    // Interacting with these things pulls items from volatile unrecorded space
                    // Coal bag could be filled from bank to use in smithing, or filled from mining
                    // Price needs to be recorded while banking or otherwise
                    case "empty":
                    case "fill":
                    case "use":
                        // Ensure item containers
                        skipTickForProfitCalculation = false;
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
