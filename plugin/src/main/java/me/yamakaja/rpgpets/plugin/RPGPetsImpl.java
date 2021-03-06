package me.yamakaja.rpgpets.plugin;

import com.comphenix.protocol.ProtocolLibrary;
import me.yamakaja.rpgpets.api.NMSHandler;
import me.yamakaja.rpgpets.api.RPGPets;
import me.yamakaja.rpgpets.api.config.ConfigGeneral;
import me.yamakaja.rpgpets.api.config.ConfigManager;
import me.yamakaja.rpgpets.api.entity.PetManager;
import me.yamakaja.rpgpets.api.entity.PetType;
import me.yamakaja.rpgpets.api.hook.FeudalHook;
import me.yamakaja.rpgpets.api.hook.Hooks;
import me.yamakaja.rpgpets.api.hook.TownyHook;
import me.yamakaja.rpgpets.api.hook.WorldGuardHook;
import me.yamakaja.rpgpets.api.item.EggManager;
import me.yamakaja.rpgpets.api.item.RPGPetsItem;
import me.yamakaja.rpgpets.api.item.CraftingRevivalManager;
import me.yamakaja.rpgpets.api.item.InventoryRevivalManager;
import me.yamakaja.rpgpets.api.logging.ErrorLogHandler;
import me.yamakaja.rpgpets.api.logging.SentryManager;
import me.yamakaja.rpgpets.api.util.EnchantmentGlow;
import me.yamakaja.rpgpets.plugin.command.CommandRPGPets;
import me.yamakaja.rpgpets.plugin.command.ReloadPreprocessor;
import me.yamakaja.rpgpets.plugin.protocol.EntitySpawnPacketTranslator;
import me.yamakaja.rpgpets.plugin.version.UpdateChecker;
import me.yamakaja.rpgpets.v1_11_R1.NMSHandler_v1_11_R1;
import me.yamakaja.rpgpets.v1_12_R1.NMSHandler_v1_12_R1;
import org.bstats.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Created by Yamakaja on 10.06.17.
 */
@SuppressWarnings("unused")
public class RPGPetsImpl extends JavaPlugin implements RPGPets {

    private SentryManager sentryManager;

    private NMSHandler nmsHandler;
    private ConfigManager configManager;

    private PetManager petManager;
    private EggManager eggManager;

    private boolean initialized;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        try {
            this.configManager.injectConfigs();
        } catch (InvalidConfigurationException e) {
            this.getLogger().severe("=====================================");
            this.getLogger().severe("");
            this.getLogger().severe("There was an error parsing the");
            this.getLogger().severe("RPGPets configuration files:");
            this.getLogger().severe("");
            Arrays.stream(e.getMessage().split("\n")).forEach(message -> this.getLogger().severe(message));
            this.getLogger().severe("");
            this.getLogger().severe("Disabling plugin ...");
            this.getLogger().severe("");
            this.getLogger().severe("=====================================");
            this.getPluginLoader().disablePlugin(this);
            return;
        } catch (IOException e) {
            this.getLogger().severe("=====================================");
            this.getLogger().severe("");
            this.getLogger().severe("An error occurred while reading");
            this.getLogger().severe("a config file:");
            Arrays.stream(e.toString().split("\n")).forEach(message -> this.getLogger().severe(message));
            this.getLogger().severe("");
            this.getLogger().severe("=====================================");
            return;
        }

        this.getLogger().info("Configs loaded!");

        this.sentryManager = new SentryManager(this, "%%__USER__%%");
        Logger.getLogger("").addHandler(new ErrorLogHandler(this.sentryManager));

        if (ConfigGeneral.ENABLE_METRICS.getAsBoolean())
            new Metrics(this);

        this.getCommand("rpgpets").setExecutor(new CommandRPGPets(this));
        this.getServer().getPluginManager().registerEvents(new ReloadPreprocessor(), this);

        this.sentryManager.recordInitializationCrumb("Loading NMSHandler");
        if (!this.loadNMSHandler()) {
            this.getPluginLoader().disablePlugin(this);
            return;
        }
        this.sentryManager.recordInitializationCrumb("Loaded NMSHandler for version " + this.getNMSHandler().getNMSVersion());

        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            this.getLogger().info("WorldGuard detected! Enabling integration!");
            this.sentryManager.recordInitializationCrumb("Initializing WorldGuard hook");
            try {
                WorldGuardHook.initialize();
                Hooks.WORLDGUARD.enable();
            } catch (Exception e) {
                this.getLogger().severe("An error occurred while trying to enable WorldGuard hook: ");
                e.printStackTrace();
            }
        }

        if (Bukkit.getPluginManager().getPlugin("Feudal") != null) {
            this.getLogger().info("Feudal detected! Enabling integration!");
            this.sentryManager.recordInitializationCrumb("Initializing Feudal hook!");
            FeudalHook.initialize();
            Hooks.FEUDAL.enable();
        }

        try {
            Class.forName("com.palmergames.bukkit.towny.Towny");
            this.getLogger().info("Towny detected! Enabling integration!");
            this.sentryManager.recordInitializationCrumb("Initializing Towny hook!");
            TownyHook.initialize();
            Hooks.TOWNY.enable();
        } catch (ClassNotFoundException e) {
            // Fail silently ...
        }

        RPGPetsItem.initialize(this);

        this.getLogger().info("Loaded for NMS version " + this.getNMSHandler().getNMSVersion() + "!");

        this.sentryManager.recordInitializationCrumb("Registering packet handler with ProtocolLib " + ProtocolLibrary.getPlugin().getDescription().getVersion());
        ProtocolLibrary.getProtocolManager().addPacketListener(new EntitySpawnPacketTranslator(this));

        this.sentryManager.recordInitializationCrumb("Registering pets");
        this.registerPets();
        this.getLogger().info("Registered pet entities!");

        this.sentryManager.recordInitializationCrumb("Registering managers");
        this.petManager = new PetManager(this);
        this.eggManager = new EggManager(this);

        if (!ConfigGeneral.ENABLE_ALTERNATIVE_REVIVAL.getAsBoolean())
            new CraftingRevivalManager(this);
        else
            new InventoryRevivalManager(this);

        Bukkit.getOnlinePlayers().forEach(p -> this.eggManager.update(p));

        if (!ConfigGeneral.ENABLE_UPDATE_CHECKER.isPresent() || ConfigGeneral.ENABLE_UPDATE_CHECKER.getAsBoolean()) {
            this.sentryManager.recordInitializationCrumb("Initializing update checker");
            new UpdateChecker(this);
        }

        this.getLogger().info("Successfully enabled RPGPets!");
        this.sentryManager.clearContext();

        this.initialized = true;

        EnchantmentGlow.getGlow();
    }

    @Override
    public void onDisable() {
        if (!this.initialized)
            return;

        this.sentryManager.recordShutdownCrumb("Cleaning up pets");

        if (this.getPetManager() != null)
            this.getPetManager().cleanup();
    }

    private void registerPets() {
        for (PetType petType : PetType.values())
            this.getNMSHandler().getPetRegistry().registerEntity(petType.getEntityId(), petType.getBaseType(), petType.getEntityClass(), petType.getEntityName());
    }

    /**
     * Loads the {@link NMSHandler} for the current version
     *
     * @return Whether loading a suitable handler was successful
     */
    private boolean loadNMSHandler() {
        String nmsVersion;
        try {
            nmsVersion = Bukkit.getServer().getClass().getPackage().getName().split(Pattern.quote("."))[3];
        } catch (Exception ex) {
            this.getLogger().log(Level.SEVERE, "An error occurred while determining server version! Disabling plugin ...", ex);
            return false;
        }

        switch (nmsVersion) {
            case "v1_11_R1":
                this.nmsHandler = new NMSHandler_v1_11_R1(this);
                break;
            case "v1_12_R1":
                this.nmsHandler = new NMSHandler_v1_12_R1(this);
                break;
            default:
                this.getLogger().severe("*****************************************************");
                this.getLogger().severe("Unsupported version: \"" + nmsVersion + "\". Disabling plugin!");
                this.getLogger().severe("*****************************************************");
                return false;
        }
        return true;
    }

    @Override
    public NMSHandler getNMSHandler() {
        return this.nmsHandler;
    }

    @Override
    public ConfigManager getConfigManager() {
        return this.configManager;
    }

    @Override
    public PetManager getPetManager() {
        return this.petManager;
    }

    @Override
    public SentryManager getSentryManager() {
        return this.sentryManager;
    }

}
