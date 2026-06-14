package com.watchmen.addon;

import com.watchmen.addon.modules.LogoBuilder;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class WmLogoBuilder extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Watchmen");
    public static final HudGroup HUD_GROUP = new HudGroup("Watchmen");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Meteor Watchmen Logo Builder");

        Modules.get().add(new LogoBuilder());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.watchmen.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("nedarim2005", "wm-logo-addon");
    }
}