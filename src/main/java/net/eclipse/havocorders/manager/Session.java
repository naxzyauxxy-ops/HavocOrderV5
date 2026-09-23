package net.eclipse.havocorders.manager;

import net.eclipse.havocorders.model.Order;
import net.eclipse.havocorders.model.SortOption;
import net.eclipse.havocorders.util.Category;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Per-player dialog state.
 *
 * Dialogs are stateless on the client, so every screen is rebuilt when it is shown.
 * Keeping the filters, page numbers and the in-progress order here means a page turn
 * is a list slice rather than a full recompute, and nothing is stored on the item stacks.
 */
public class Session {

    private final UUID playerId;

    // Order board
    private SortOption sort = SortOption.RECENTLY_LISTED;
    private Category filter = Category.ALL;
    private String query = "";
    private int page;

    // Cached board results, only recomputed when the order set or the filters change.
    private List<Order> cachedBoard = List.of();
    private long cachedVersion = -1L;
    private String cachedSignature = "";

    // Item picker
    private int itemPage;
    private Category itemFilter = Category.ALL;
    private String itemQuery = "";

    // Enchantment picker
    private int enchantPage;

    // New order draft
    private ItemStack draftItem;
    private int draftAmount = 1;
    private double draftPrice = 1.0D;

    // Your orders / collect
    private int myOrdersPage;
    private int collectPage;

    public Session(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    // ------------------------------------------------------------------ board

    public SortOption getSort() {
        return sort;
    }

    public void setSort(SortOption sort) {
        this.sort = sort;
        this.page = 0;
    }

    public Category getFilter() {
        return filter;
    }

    public void setFilter(Category filter) {
        this.filter = filter;
        this.page = 0;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query;
        this.page = 0;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = Math.max(0, page);
    }

    /** Signature of everything that affects the board result set. */
    private String signature() {
        return sort.name() + '|' + filter.name() + '|' + query.toLowerCase();
    }

    public boolean isBoardStale(long version) {
        return version != cachedVersion || !signature().equals(cachedSignature);
    }

    public void cacheBoard(List<Order> orders, long version) {
        this.cachedBoard = orders;
        this.cachedVersion = version;
        this.cachedSignature = signature();
    }

    public List<Order> getCachedBoard() {
        return cachedBoard;
    }

    // ------------------------------------------------------------------ item picker

    public int getItemPage() {
        return itemPage;
    }

    public void setItemPage(int itemPage) {
        this.itemPage = Math.max(0, itemPage);
    }

    public Category getItemFilter() {
        return itemFilter;
    }

    public void setItemFilter(Category itemFilter) {
        this.itemFilter = itemFilter;
        this.itemPage = 0;
    }

    public String getItemQuery() {
        return itemQuery;
    }

    public void setItemQuery(String itemQuery) {
        this.itemQuery = itemQuery == null ? "" : itemQuery;
        this.itemPage = 0;
    }

    public int getEnchantPage() {
        return enchantPage;
    }

    public void setEnchantPage(int enchantPage) {
        this.enchantPage = Math.max(0, enchantPage);
    }

    // ------------------------------------------------------------------ draft

    public ItemStack getDraftItem() {
        return draftItem;
    }

    public void setDraftItem(ItemStack draftItem) {
        this.draftItem = draftItem == null ? null : draftItem.clone();
    }

    public int getDraftAmount() {
        return draftAmount;
    }

    public void setDraftAmount(int draftAmount) {
        this.draftAmount = Math.max(1, draftAmount);
    }

    public double getDraftPrice() {
        return draftPrice;
    }

    public void setDraftPrice(double draftPrice) {
        this.draftPrice = Math.max(0.0D, draftPrice);
    }

    public double getDraftTotal() {
        return draftAmount * draftPrice;
    }

    public void clearDraft() {
        draftItem = null;
        draftAmount = 1;
        draftPrice = 1.0D;
    }

    // ------------------------------------------------------------------ pages

    public int getMyOrdersPage() {
        return myOrdersPage;
    }

    public void setMyOrdersPage(int myOrdersPage) {
        this.myOrdersPage = Math.max(0, myOrdersPage);
    }

    public int getCollectPage() {
        return collectPage;
    }

    public void setCollectPage(int collectPage) {
        this.collectPage = Math.max(0, collectPage);
    }
}
