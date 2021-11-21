package org.samo_lego.taterzens.gui;

import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.samo_lego.taterzens.Taterzens.config;

public abstract class ListItemsGUI extends SimpleGui implements Container {
    protected static final CompoundTag customData = new CompoundTag();
    private int currentPage = 0;

    /**
     * Constructs a new simple container gui for the supplied player.
     *
     * @param player the player to server this gui to.
     * @param npcName player's taterzen.
     * @param titleTranslationKey title translation key for gui.
     */
    public ListItemsGUI(ServerPlayer player, Component npcName, String titleTranslationKey) {
        super(MenuType.GENERIC_9x6, player, false);

        this.setTitle(new TranslatableComponent(titleTranslationKey).append(": ").withStyle(ChatFormatting.YELLOW).append(npcName.copy()));

        // Info (which page)
        ItemStack info = new ItemStack(Items.PAPER);
        info.setTag(customData.copy());
        info.setHoverName(getCurrentPageMarker());
        info.enchant(null, 0);

        this.setSlot(3, info);

        // Previous page
        ItemStack back = new ItemStack(Items.MAGENTA_GLAZED_TERRACOTTA);
        back.setTag(customData.copy());
        back.setHoverName(new TranslatableComponent("spectatorMenu.previous_page"));
        back.enchant(null, 0);

        GuiElement previousScreenButton = new GuiElement(back, (index, type1, action) -> {
            if (--this.currentPage < 0)
                this.currentPage = 0;
            info.setHoverName(getCurrentPageMarker());
        });
        this.setSlot(0, previousScreenButton);

        // Next page
        ItemStack next = new ItemStack(Items.LIGHT_BLUE_GLAZED_TERRACOTTA);
        next.setTag(customData.copy());
        next.setHoverName(new TranslatableComponent("spectatorMenu.next_page"));
        next.enchant(null, 0);

        GuiElement nextScreenButton = new GuiElement(next, (_i, _clickType, _slotActionType) -> {
            if (++this.currentPage > this.getMaxPages())
                this.currentPage = this.getMaxPages();
            info.setHoverName(getCurrentPageMarker());
        });
        this.setSlot(1, nextScreenButton);


        // Close screen button
        ItemStack close = new ItemStack(Items.STRUCTURE_VOID);
        close.setTag(customData.copy());
        close.setHoverName(new TranslatableComponent("spectatorMenu.close"));
        close.enchant(null, 0);

        GuiElement closeScreenButton = new GuiElement(close, (_i, _clickType, _slotActionType) -> {
            this.close();
            player.closeContainer();
        });
        this.setSlot(8, closeScreenButton);
    }

    /**
     * Gets current page info (Page X of Y)
     * @return translated page info text.
     */
    private TranslatableComponent getCurrentPageMarker() {
        return new TranslatableComponent("book.pageIndicator", this.currentPage + 1, this.getMaxPages() + 1);
    }

    public int getCurrentPage() {
       return this.currentPage;
    }


    @Override
    public int getContainerSize() {
        return 9 * 6;
    }

    protected int getSlot2MessageIndex(int slotIndex) {
        return this.getCurrentPage() * this.getSize() + slotIndex;
    }

    @Override
    public void setChanged() {
    }

    @Override
    public boolean stillValid(Player player) {
        return false;
    }

    public abstract int getMaxPages();


    static {
        customData.putInt("CustomModelData", config.guiItemModelData);
        customData.putInt("HideFlags", 127);
    }
}