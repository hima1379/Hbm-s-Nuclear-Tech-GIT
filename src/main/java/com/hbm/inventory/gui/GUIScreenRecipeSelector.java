package com.hbm.inventory.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.hbm.inventory.recipes.FusionRecipe;
import com.hbm.inventory.recipes.FusionRecipes;
import com.hbm.items.ModItems;
import com.hbm.items.machine.ItemFusionTemplate;
import com.hbm.lib.RefStrings;
import com.hbm.packet.NBTControlPacket;
import com.hbm.packet.PacketDispatcher;
import com.hbm.util.I18nUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

/**
 * GUI for selecting fusion recipes - Adapted for 1.12.2
 * Displays all available fusion recipes in a grid and sends selection to server.
 */
public class GUIScreenRecipeSelector extends GuiScreen {

    protected static final ResourceLocation texture = new ResourceLocation(RefStrings.MODID + ":textures/gui/gui_planner.png");
    protected int xSize = 176;
    protected int ySize = 229;
    protected int guiLeft;
    protected int guiTop;

    private final EntityPlayer player;
    private final TileEntity machine;
    private final BlockPos machinePos;
    private final int selectorIndex;
    private final String currentRecipe;
    private final List<String> availableRecipes;
    private final GuiScreen parentGui;

    private List<RecipeButton> buttons = new ArrayList<RecipeButton>();
    private int currentPage = 0;

    /**
     * Opens the recipe selector GUI
     *
     * @param machine The tile entity to send recipe selection to
     * @param currentRecipe Currently selected recipe name (can be null)
     * @param selectorIndex Index of the recipe selector (0 for main fusion recipe)
     * @param availableRecipes List of available recipe names (null = all recipes)
     * @param parentGui Parent GUI to return to when closed (can be null)
     */
    public static void openSelector(TileEntity machine, String currentRecipe, int selectorIndex, List<String> availableRecipes, GuiScreen parentGui) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.displayGuiScreen(new GUIScreenRecipeSelector(mc.player, machine, currentRecipe, selectorIndex, availableRecipes, parentGui));
    }

    public GUIScreenRecipeSelector(EntityPlayer player, TileEntity machine, String currentRecipe, int selectorIndex, List<String> availableRecipes, GuiScreen parentGui) {
        this.player = player;
        this.machine = machine;
        this.machinePos = machine.getPos();
        this.currentRecipe = currentRecipe;
        this.selectorIndex = selectorIndex;
        this.parentGui = parentGui;

        // Build list of available recipes
        this.availableRecipes = new ArrayList<>();

        if(availableRecipes != null && !availableRecipes.isEmpty()) {
            // Use provided recipe list (blueprint-filtered)
            this.availableRecipes.addAll(availableRecipes);
        } else {
            // Show all fusion recipes
            int count = FusionRecipes.INSTANCE.getRecipeCount();
            for(int i = 0; i < count; i++) {
                FusionRecipe recipe = FusionRecipes.INSTANCE.getRecipeByIndex(i);
                if(recipe != null) {
                    this.availableRecipes.add(recipe.getName());
                }
            }
        }
    }

    int getPageCount() {
        return (int)Math.ceil((availableRecipes.size() - 1) / (5 * 7));
    }

    @Override
    public void updateScreen() {
        if(currentPage < 0)
            currentPage = 0;
        if(currentPage > getPageCount())
            currentPage = getPageCount();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float f) {
        this.drawDefaultBackground();
        this.drawGuiContainerBackgroundLayer(f, mouseX, mouseY);
        this.drawGuiContainerForegroundLayer(mouseX, mouseY);

        // Draw tooltips last (on top of everything)
        for(RecipeButton b : buttons) {
            if(b.isMouseOnButton(mouseX, mouseY)) {
                b.drawTooltip(mouseX, mouseY);
            }
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop = (this.height - this.ySize) / 2;

        updateButtons();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    protected void updateButtons() {
        buttons.clear();


        // Create buttons for recipes on current page (5x7 grid = 35 per page)
        for(int i = currentPage * 35; i < Math.min(currentPage * 35 + 35, availableRecipes.size()); i++) {
            String recipeName = availableRecipes.get(i);
            int recipeIndex = getRecipeIndex(recipeName);

            if(recipeIndex >= 0) {
                ItemStack templateStack = ItemFusionTemplate.getTemplate(recipeIndex);
                boolean selected = recipeName.equals(currentRecipe);

                int buttonX = guiLeft + 25 + (27 * (i % 5));
                int buttonY = guiTop + 26 + (27 * (int)Math.floor((i / 5D))) - currentPage * 27 * 7;

                buttons.add(new RecipeButton(buttonX, buttonY, templateStack, recipeName, selected));
            }
        }

        // Pagination buttons
        if(currentPage > 0) {
            buttons.add(new RecipeButton(guiLeft + 25 - 18, guiTop + 26 + (27 * 3), 1, "Previous"));
        }
        if(currentPage < getPageCount()) {
            buttons.add(new RecipeButton(guiLeft + 25 + (27 * 4) + 18, guiTop + 26 + (27 * 3), 2, "Next"));
        }
    }

    /**
     * Gets the recipe index from recipe name
     */
    private int getRecipeIndex(String recipeName) {
        int count = FusionRecipes.INSTANCE.getRecipeCount();
        for(int i = 0; i < count; i++) {
            FusionRecipe recipe = FusionRecipes.INSTANCE.getRecipeByIndex(i);
            if(recipe != null && recipe.getName().equals(recipeName)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void mouseClicked(int i, int j, int k) {
        try {
            for(RecipeButton b : buttons) {
                if(b.isMouseOnButton(i, j)) {
                    b.executeAction();
                }
            }
        } catch (Exception ex) {
            updateButtons();
        }
    }

    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // Draw page number
        String pageText = (currentPage + 1) + "/" + (getPageCount() + 1);
        this.fontRenderer.drawString(pageText,
                guiLeft + this.xSize / 2 - this.fontRenderer.getStringWidth(pageText) / 2,
                guiTop + 10, 4210752);
    }

    protected void drawGuiContainerBackgroundLayer(float f, int mouseX, int mouseY) {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);

        // Draw all buttons
        for(RecipeButton b : buttons) {
            b.drawButton(b.isMouseOnButton(mouseX, mouseY));
        }
        for(RecipeButton b : buttons) {
            b.drawIcon(b.isMouseOnButton(mouseX, mouseY));
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if(keyCode == 1 || keyCode == this.mc.gameSettings.keyBindInventory.getKeyCode()) {
            // ESC or inventory key - return to parent GUI
            if(parentGui != null) {
                this.mc.displayGuiScreen(parentGui);
            } else {
                this.mc.player.closeScreen();
            }
        }
    }

    /**
     * Sends recipe selection to server
     */
    private void selectRecipe(String recipeName) {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setInteger("index", selectorIndex);
        nbt.setString("selection", recipeName);

        PacketDispatcher.wrapper.sendToServer(new NBTControlPacket(nbt, machinePos));

        // Return to parent GUI
        if(parentGui != null) {
            this.mc.displayGuiScreen(parentGui);
        } else {
            this.mc.player.closeScreen();
        }
    }

    /**
     * Inner class for recipe selection buttons
     */
    class RecipeButton {

        int xPos;
        int yPos;
        int type; // 0: recipe, 1: prev, 2: next
        String info;
        ItemStack stack;
        String recipeName;
        boolean selected;

        // Navigation button constructor (prev/next)
        public RecipeButton(int x, int y, int t, String i) {
            xPos = x;
            yPos = y;
            type = t;
            info = i;
        }

        // Recipe button constructor
        public RecipeButton(int x, int y, ItemStack stack, String recipeName, boolean selected) {
            xPos = x;
            yPos = y;
            type = 0;
            this.stack = stack.copy();
            this.recipeName = recipeName;
            this.selected = selected;

            // Get localized recipe name for tooltip
            FusionRecipe recipe = FusionRecipes.INSTANCE.getRecipe(recipeName);
            if(recipe != null) {
                this.info = I18nUtil.resolveKey(recipe.getName());
            } else {
                this.info = recipeName;
            }
        }

        public boolean isMouseOnButton(int mouseX, int mouseY) {
            return xPos <= mouseX && xPos + 18 > mouseX && yPos < mouseY && yPos + 18 >= mouseY;
        }

        public void drawButton(boolean hovered) {
            Minecraft.getMinecraft().getTextureManager().bindTexture(texture);

            if(type == 0 && selected) {
                // Draw selected highlight (gold border)
                drawTexturedModalRect(xPos, yPos, 176 + 18, 0, 18, 18);
            } else {
                // Normal button or hovered state
                drawTexturedModalRect(xPos, yPos, hovered ? 176 + 18 : 176,
                        type == 1 ? 18 : (type == 2 ? 36 : 0), 18, 18);
            }
        }

        public void drawIcon(boolean hovered) {
            try {
                RenderHelper.enableGUIStandardItemLighting();
                if(stack != null) {
                    itemRender.renderItemAndEffectIntoGUI(player, stack, xPos + 1, yPos + 1);
                }
                RenderHelper.disableStandardItemLighting();
            } catch(Exception x) { }
        }

        public void drawTooltip(int x, int y) {
            if(info == null || info.isEmpty())
                return;

            List<String> tooltip = new ArrayList<>();

            // Recipe name
            tooltip.add("§f" + info);

            // Get detailed recipe info
            if(type == 0 && recipeName != null) {
                FusionRecipe recipe = FusionRecipes.INSTANCE.getRecipe(recipeName);
                if(recipe != null) {
                    // Duration
                    tooltip.add("§cDuration: §f" + String.format("%.1fs", recipe.duration / 20.0));

                    // Consumption (solenoid power)
                    long consumption = 25000;
                    tooltip.add("§dConsumption: §f" + com.hbm.util.BobMathUtil.getShortNumber(consumption) + "HE/t");

                    // Klystron Input Energy
                    tooltip.add("§dKlystron Input Energy: §f" + com.hbm.util.BobMathUtil.getShortNumber(recipe.ignitionTemp) + "KyU/t");

                    // Plasma Output Energy
                    tooltip.add("§cPlasma Output Energy: §f" + com.hbm.util.BobMathUtil.getShortNumber(recipe.outputTemp) + "TU/t");

                    // Output Neutron Flux
                    tooltip.add("§bOutput Neutron Flux: §f" + String.format("%.1f flux/t", recipe.neutronFlux));

                    // Input fluids
                    if(recipe.inputFluids != null && recipe.inputFluids.length > 0) {
                        tooltip.add("§9Input:");
                        for(int i = 0; i < recipe.inputFluids.length; i++) {
                            if(recipe.inputFluids[i] != null && recipe.inputFluidAmounts[i] > 0) {
                                String fluidName = recipe.inputFluids[i].getLocalizedName(new net.minecraftforge.fluids.FluidStack(recipe.inputFluids[i], 1000));
                                tooltip.add("  §7" + recipe.inputFluidAmounts[i] + "mB " + fluidName);
                            }
                        }
                    }

                    // Output items/fluids
                    tooltip.add("§6Output:");
                    if(recipe.outputFluid != null && recipe.outputFluidAmount > 0) {
                        String fluidName = recipe.outputFluid.getLocalizedName(new net.minecraftforge.fluids.FluidStack(recipe.outputFluid, 1000));
                        tooltip.add("  §7" + recipe.outputFluidAmount + "mB " + fluidName);
                    }
                    if(recipe.output != null && !recipe.output.isEmpty()) {
                        tooltip.add("  §7" + recipe.output.getCount() + "x " + recipe.output.getDisplayName());
                    }
                }
            }

            if(selected) {
                tooltip.add("§a(Selected)");
            }

            drawHoveringText(tooltip, x, y);
        }

        public void executeAction() {
            mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1.0F));

            if(type == 0) {
                // Recipe selection
                selectRecipe(recipeName);
            } else if(type == 1) {
                // Previous page
                if(currentPage > 0) {
                    currentPage--;
                }
                updateButtons();
            } else if(type == 2) {
                // Next page
                if(currentPage < getPageCount()) {
                    currentPage++;
                }
                updateButtons();
            }
        }
    }
}
