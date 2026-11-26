package com.hai.selldonutsmp.modules;

import com.hai.selldonutsmp.util.ChatPrice;
import com.hai.selldonutsmp.util.Money;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Het do an -> /ah steak. Quet 45 o tren cua tung trang, chon stack 64 steak co gia nho nhat va bam vao la mua luon.
 * Trang nay khong co thi bam mui ten o o 54 sang trang ke tiep (toi da vai trang).
 * Neu chat bao "already bought" (mua cham) thi quet lai va mua tiep cho den khi tui do co du 64 steak.
 * SellAxe goi request() khi tay phu trong va het do an, tick() moi tick va onChat() cho moi dong chat.
 */
public class FoodBuyer {
    private enum State { IDLE, WAIT_AH, WAIT_ITEMS, WAIT_PAGE, WAIT_BUY }

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int SCAN_LIMIT = 45; // chi quet 45 o tren (hang duoi la nut dieu khien)

    private final Setting<Boolean> enabled;
    private final Setting<String> command;
    private final Setting<Integer> amount;
    private final Setting<Double> maxPrice;
    private final Setting<String> priceRegex;
    private final Setting<Integer> maxPages;
    private final Setting<Integer> pageSlot;
    private final Setting<Integer> maxAttempts;
    private final Setting<Integer> retrySeconds;
    private final Setting<Boolean> debug;

    private State state = State.IDLE;
    private int timer = 0;
    private int cooldown = 0;
    private int beforeCount = 0;
    private int page = 1;
    private int attempts = 0;
    private int listingSlot = -1;
    private double listingPrice = 0;
    private boolean alreadyBought = false;
    private boolean noMoney = false;
    private boolean dumped = false;
    private final Set<Integer> excluded = new HashSet<>(); // o da thu ma bi "already bought"
    private String lastRegex = null;
    private Pattern lastPattern = null;

    public FoodBuyer(SettingGroup g) {
        enabled = g.add(new BoolSetting.Builder()
            .name("tu-mua-steak-khi-het-do-an")
            .description("Khi tay phu trong va khong con thuc an trong tui do thi tu /ah, mua stack steak re nhat.")
            .defaultValue(true)
            .build()
        );
        command = g.add(new StringSetting.Builder()
            .name("lenh-tim-steak")
            .description("Lenh mo cho dau gia tim steak.")
            .defaultValue("/ah steak")
            .build()
        );
        amount = g.add(new IntSetting.Builder()
            .name("so-steak-moi-stack")
            .description("Chi mua phan dang ban dung so luong steak nay (mot stack day = 64).")
            .defaultValue(64)
            .min(1)
            .sliderMax(64)
            .build()
        );
        maxPrice = g.add(new DoubleSetting.Builder()
            .name("gia-toi-da")
            .description("Khong mua neu stack re nhat dat hon muc nay (0 = khong gioi han).")
            .defaultValue(1000000)
            .min(0)
            .sliderMax(10000000)
            .build()
        );
        priceRegex = g.add(new StringSetting.Builder()
            .name("regex-doc-gia")
            .description("Regex doc so tien nam ngay sau dau $ trong mo ta cua mon do. Nhom so 1 phai la so tien.")
            .defaultValue("(?i)^\\s*([\\d,]+(?:\\.\\d+)?[kmbt]?)(?![a-z])")
            .build()
        );
        maxPages = g.add(new IntSetting.Builder()
            .name("so-trang-toi-da")
            .description("Quet toi da bao nhieu trang cua /ah (khong co stack 64 thi bam mui ten sang trang ke).")
            .defaultValue(3)
            .min(1)
            .sliderMax(10)
            .build()
        );
        pageSlot = g.add(new IntSetting.Builder()
            .name("o-mui-ten-sang-trang")
            .description("O cua mui ten sang trang sau (tinh tu 1). DonutSMP: o 54.")
            .defaultValue(54)
            .min(1)
            .sliderMax(54)
            .build()
        );
        maxAttempts = g.add(new IntSetting.Builder()
            .name("so-lan-mua-toi-da")
            .description("Moi dot mua thu toi da bao nhieu lan (mua cham bi nguoi khac mua truoc thi thu lai).")
            .defaultValue(10)
            .min(1)
            .sliderMax(30)
            .build()
        );
        retrySeconds = g.add(new IntSetting.Builder()
            .name("thu-lai-sau-giay")
            .description("Neu ca dot mua that bai thi doi bao nhieu giay roi moi thu lai.")
            .defaultValue(120)
            .min(10)
            .sliderMax(600)
            .build()
        );
        debug = g.add(new BoolSetting.Builder()
            .name("go-loi-mua-do-an")
            .description("In ra cac buoc mua steak va mo ta mon do dau tien (de chinh khi mua sai).")
            .defaultValue(false)
            .build()
        );
    }

    public boolean isBusy() {
        return state != State.IDLE;
    }

    public void reset() {
        state = State.IDLE;
    }

    /** Yeu cau mua steak (bo qua neu dang ban, dang cho, hoac dang mo giao dien khac). */
    public void request() {
        if (!enabled.get() || state != State.IDLE || cooldown > 0) return;
        if (mc.player == null || mc.currentScreen != null) return;

        AutoSellModule sell = Modules.get().get(AutoSellModule.class);
        if (sell != null && sell.isBusy()) return;
        AutoPayModule pay = Modules.get().get(AutoPayModule.class);
        if (pay != null && pay.isBusy()) return;

        beforeCount = countSteak();
        attempts = 0;
        ChatUtils.info("Het do an, dang tim steak tren /ah...");
        openSearch();
    }

    private void openSearch() {
        String cmd = command.get().trim();
        if (!cmd.startsWith("/")) cmd = "/" + cmd;
        ChatUtils.sendPlayerMsg(cmd);

        page = 1;
        excluded.clear();
        alreadyBought = false;
        noMoney = false;
        dumped = false;
        state = State.WAIT_AH;
        timer = 100;
    }

    /** SellAxe chuyen moi dong chat vao day (da chuyen thanh chu thuong). */
    public void onChat(String msg) {
        if (state != State.WAIT_BUY) return;
        if (msg.contains("already bought")) {
            alreadyBought = true;
        } else if (msg.contains("enough money") || msg.contains("cannot afford") || msg.contains("can't afford") || msg.contains("insufficient")) {
            noMoney = true;
        }
    }

    public void tick() {
        if (cooldown > 0) cooldown--;
        if (mc.player == null || mc.interactionManager == null) return;

        switch (state) {
            case IDLE -> {}
            case WAIT_AH -> {
                if (containerScreen() != null) {
                    state = State.WAIT_ITEMS;
                    timer = 15; // cho server do danh sach vao giao dien
                } else if (--timer <= 0) {
                    fail("Khong mo duoc giao dien /ah.");
                }
            }
            case WAIT_ITEMS, WAIT_PAGE -> {
                if (--timer <= 0) scan();
            }
            case WAIT_BUY -> waitBuy();
        }
    }

    // ---------- Cac buoc ----------

    // Quet trang hien tai: mua stack du so luong re nhat; khong co thi sang trang ke
    private void scan() {
        GenericContainerScreen screen = containerScreen();
        if (screen == null) {
            reopen("Giao dien /ah da dong.");
            return;
        }
        GenericContainerScreenHandler h = screen.getScreenHandler();
        int limit = Math.min(SCAN_LIMIT, h.getRows() * 9);

        int bestSlot = -1;
        double best = Double.MAX_VALUE;
        int full = 0;
        int unpriced = 0;
        for (int i = 0; i < limit; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (debug.get() && !dumped && st.isOf(Items.COOKED_BEEF)) {
                dumped = true;
                dump(st, i);
            }
            if (excluded.contains(i) || !isListing(st)) continue;
            full++;

            double price = readPrice(st);
            if (price < 0) {
                unpriced++;
                continue;
            }
            if (price < best) {
                best = price;
                bestSlot = i;
            }
        }

        if (debug.get()) ChatUtils.info("Trang " + page + ": " + full + " phan " + amount.get() + " steak, " + unpriced + " phan khong doc duoc gia.");

        if (bestSlot >= 0) {
            if (maxPrice.get() > 0 && best > maxPrice.get()) {
                fail("Stack steak re nhat co gia " + Money.format(best) + ", cao hon gia-toi-da " + Money.format(maxPrice.get()) + ".");
                return;
            }
            buy(h, bestSlot, best);
            return;
        }

        if (unpriced > 0) {
            ChatUtils.warning("Co " + unpriced + " phan " + amount.get() + " steak nhung khong doc duoc gia, bat go-loi-mua-do-an va gui dong [lore] cho tac gia.");
        }

        // Trang nay khong co -> bam mui ten o o 54 sang trang sau
        if (page >= maxPages.get()) {
            fail("Khong thay stack " + amount.get() + " steak trong " + page + " trang dau cua /ah.");
            return;
        }
        int idx = pageSlot.get() - 1;
        if (idx < 0 || idx >= h.getRows() * 9 || !h.getSlot(idx).getStack().isOf(Items.ARROW)) {
            fail("Khong thay mui ten sang trang o o " + pageSlot.get() + ".");
            return;
        }
        if (debug.get()) ChatUtils.info("Trang " + page + " khong co, bam mui ten sang trang " + (page + 1) + ".");
        click(h, idx);
        page++;
        excluded.clear();
        state = State.WAIT_PAGE;
        timer = 15;
    }

    // Bam vao mon: chi can click la mua luon
    private void buy(GenericContainerScreenHandler h, int slot, double price) {
        attempts++;
        ChatUtils.info("Mua stack " + amount.get() + " steak gia " + Money.format(price) + " (re nhat trang " + page + ", lan " + attempts + ").");
        click(h, slot);
        listingSlot = slot;
        listingPrice = price;
        alreadyBought = false;
        noMoney = false;
        state = State.WAIT_BUY;
        timer = 30;
    }

    // Cho ket qua: co du steak -> xong; "already bought" hoac khong thay gi -> quet va mua lai
    private void waitBuy() {
        if (noMoney) {
            fail("Khong du tien mua steak.");
            return;
        }
        if (countSteak() >= beforeCount + amount.get()) {
            success();
            return;
        }
        if (alreadyBought || --timer <= 0) {
            excluded.add(listingSlot); // bo qua o vua thu, tim phan re ke tiep
            if (attempts >= maxAttempts.get()) {
                fail("Thu mua " + attempts + " lan van chua co steak.");
                return;
            }
            if (debug.get()) ChatUtils.info(alreadyBought ? "Bi mua truoc (already bought), quet lai." : "Khong thay steak ve tui, quet lai.");
            alreadyBought = false;
            state = State.WAIT_ITEMS;
            timer = 8; // cho giao dien cap nhat roi quet lai
        }
    }

    private void success() {
        closeScreen();
        ChatUtils.info("Da mua " + amount.get() + " steak (" + Money.format(listingPrice) + "), se dua sang tay phu.");
        cooldown = 100;
        state = State.IDLE;
    }

    private void reopen(String reason) {
        if (attempts >= maxAttempts.get()) {
            fail(reason);
            return;
        }
        attempts++;
        ChatUtils.info(reason + " Mo lai /ah.");
        openSearch();
    }

    private void fail(String reason) {
        ChatUtils.warning(reason);
        closeScreen();
        state = State.IDLE;
        cooldown = retrySeconds.get() * 20;
    }

    // ---------- Tien ich ----------

    private GenericContainerScreen containerScreen() {
        return mc.currentScreen instanceof GenericContainerScreen s ? s : null;
    }

    private void closeScreen() {
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
    }

    private void click(GenericContainerScreenHandler h, int slot) {
        mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
    }

    private boolean isListing(ItemStack st) {
        return st.isOf(Items.COOKED_BEEF) && st.getCount() == amount.get();
    }

    // Doc gia tu lore: uu tien dong co chu price/gia, neu khong thi dong dau tien doc duoc
    private double readPrice(ItemStack st) {
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return -1;

        double fallback = -1;
        for (Text line : lore.lines()) {
            String after = ChatPrice.after(line, false);              // dau $ bat ky
            if (after == null) after = ChatPrice.after(line, true);   // hoac ky hieu xanh la dung thay dau $
            if (after == null) continue;

            Matcher m = pricePattern().matcher(after);
            if (!m.find()) continue;
            double v;
            try {
                v = Money.parse(m.group(1));
            } catch (Exception e) {
                continue;
            }
            String low = line.getString().toLowerCase();
            if (low.contains("price") || low.contains("gia")) return v;
            if (fallback < 0) fallback = v;
        }
        return fallback;
    }

    // In mo ta mon steak dau tien (ten + lore + mau) de chinh cach doc gia
    private void dump(ItemStack st, int slot) {
        ChatUtils.info("[mon] o " + (slot + 1) + ", so luong " + st.getCount());
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) {
            ChatUtils.info("[lore] (khong co)");
            return;
        }
        for (Text line : lore.lines()) ChatUtils.info("[lore] %s", ChatPrice.describe(line));
    }

    private Pattern pricePattern() {
        String r = priceRegex.get();
        if (!r.equals(lastRegex)) {
            try {
                lastPattern = Pattern.compile(r);
            } catch (Exception e) {
                ChatUtils.warning("regex-doc-gia khong hop le.");
                lastPattern = Pattern.compile("a^");
            }
            lastRegex = r;
        }
        return lastPattern;
    }

    // Dem steak trong tui do (neu dang mo giao dien thi dem o phan tui do cua giao dien do)
    private int countSteak() {
        int n = 0;
        GenericContainerScreen s = containerScreen();
        if (s != null) {
            GenericContainerScreenHandler h = s.getScreenHandler();
            for (int i = h.getRows() * 9; i < h.slots.size(); i++) {
                ItemStack st = h.getSlot(i).getStack();
                if (st.isOf(Items.COOKED_BEEF)) n += st.getCount();
            }
            return n;
        }
        for (int i = 9; i <= 44; i++) {
            ItemStack st = mc.player.playerScreenHandler.getSlot(i).getStack();
            if (st.isOf(Items.COOKED_BEEF)) n += st.getCount();
        }
        return n;
    }
}
