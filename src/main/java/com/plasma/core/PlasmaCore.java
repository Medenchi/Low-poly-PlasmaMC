package com.plasma.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;

public class PlasmaCore extends JavaPlugin implements Listener, CommandExecutor {

    private static PlasmaCore instance;
    private Connection db;
    
    // Данные игроков
    private final Set<UUID> loggedIn = new HashSet<>();
    private final Set<UUID> frozen = new HashSet<>();
    private final Map<UUID, UUID> tpaRequests = new HashMap<>(); // target -> requester
    private final Map<UUID, Scoreboard> playerBoards = new HashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        getLogger().info("§b================================");
        getLogger().info("§b    PLASMACORE v1.0.0");
        getLogger().info("§b================================");

        // База данных
        initDatabase();
        
        // События
        Bukkit.getPluginManager().registerEvents(this, this);
        
        // Команды
        String[] commands = {"register", "login", "home", "sethome", "delhome", "homes", 
                            "spawn", "setspawn", "tpa", "tpaccept", "tpdeny", 
                            "balance", "pay", "plasmareload"};
        for (String cmd : commands) {
            if (getCommand(cmd) != null) {
                getCommand(cmd).setExecutor(this);
            }
        }

        // Таски
        startTasks();

        getLogger().info("§a✓ PlasmaCore запущен!");
    }

    @Override
    public void onDisable() {
        try {
            if (db != null && !db.isClosed()) db.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        getLogger().info("§c✗ PlasmaCore отключён");
    }

    // ============================================================
    //                      DATABASE
    // ============================================================

    private void initDatabase() {
        try {
            File file = new File(getDataFolder(), "plasma.db");
            db = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            
            Statement stmt = db.createStatement();
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    password TEXT,
                    coins REAL DEFAULT 100,
                    last_ip TEXT
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS homes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    name TEXT NOT NULL,
                    world TEXT NOT NULL,
                    x REAL, y REAL, z REAL,
                    yaw REAL, pitch REAL,
                    UNIQUE(uuid, name)
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS spawn (
                    id INTEGER PRIMARY KEY DEFAULT 1,
                    world TEXT, x REAL, y REAL, z REAL, yaw REAL, pitch REAL
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    uuid TEXT PRIMARY KEY,
                    ip TEXT NOT NULL,
                    expires INTEGER NOT NULL
                )
            """);
            stmt.close();
            getLogger().info("§a✓ База данных готова");
        } catch (SQLException e) {
            getLogger().severe("Ошибка БД: " + e.getMessage());
        }
    }

    // ============================================================
    //                      COMMANDS
    // ============================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игроков!");
            return true;
        }

        String cmd = command.getName().toLowerCase();

        // Команды без авторизации
        if (cmd.equals("register")) return cmdRegister(player, args);
        if (cmd.equals("login")) return cmdLogin(player, args);

        // Остальные требуют авторизации
        if (!loggedIn.contains(player.getUniqueId())) {
            msg(player, "&cСначала авторизуйтесь!");
            return true;
        }

        switch (cmd) {
            case "home" -> cmdHome(player, args);
            case "sethome" -> cmdSetHome(player, args);
            case "delhome" -> cmdDelHome(player, args);
            case "homes" -> cmdHomes(player);
            case "spawn" -> cmdSpawn(player);
            case "setspawn" -> cmdSetSpawn(player);
            case "tpa" -> cmdTpa(player, args);
            case "tpaccept" -> cmdTpAccept(player);
            case "tpdeny" -> cmdTpDeny(player);
            case "balance" -> cmdBalance(player, args);
            case "pay" -> cmdPay(player, args);
            case "plasmareload" -> cmdReload(player);
        }
        return true;
    }

    private boolean cmdRegister(Player player, String[] args) {
        UUID uuid = player.getUniqueId();
        
        if (isRegistered(uuid)) {
            msg(player, "&cВы уже зарегистрированы!");
            return true;
        }
        if (args.length < 1) {
            msg(player, "&cИспользование: /register <пароль>");
            return true;
        }
        if (args[0].length() < 4) {
            msg(player, "&cПароль минимум 4 символа!");
            return true;
        }

        try {
            PreparedStatement ps = db.prepareStatement(
                "INSERT OR REPLACE INTO players (uuid, name, password, coins) VALUES (?, ?, ?, ?)"
            );
            ps.setString(1, uuid.toString());
            ps.setString(2, player.getName());
            ps.setString(3, hashPassword(args[0]));
            ps.setDouble(4, getConfig().getDouble("coins.start-balance", 100));
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        login(player);
        msg(player, "&a✓ Вы успешно зарегистрировались!");
        return true;
    }

    private boolean cmdLogin(Player player, String[] args) {
        UUID uuid = player.getUniqueId();

        if (loggedIn.contains(uuid)) {
            msg(player, "&aВы уже авторизованы!");
            return true;
        }
        if (!isRegistered(uuid)) {
            msg(player, "&cВы не зарегистрированы! /register <пароль>");
            return true;
        }
        if (args.length < 1) {
            msg(player, "&cИспользование: /login <пароль>");
            return true;
        }

        String stored = getPassword(uuid);
        if (!hashPassword(args[0]).equals(stored)) {
            msg(player, "&cНеверный пароль!");
            return true;
        }

        login(player);
        msg(player, "&a✓ Добро пожаловать на Plasma!");
        return true;
    }

    private void cmdHome(Player player, String[] args) {
        String name = args.length > 0 ? args[0].toLowerCase() : "home";
        UUID uuid = player.getUniqueId();

        try {
            PreparedStatement ps = db.prepareStatement(
                "SELECT * FROM homes WHERE uuid = ? AND name = ?"
            );
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ResultSet rs = ps.executeQuery();

            if (!rs.next()) {
                msg(player, "&cДом '&e" + name + "&c' не найден!");
                rs.close(); ps.close();
                return;
            }

            World world = Bukkit.getWorld(rs.getString("world"));
            if (world == null) {
                msg(player, "&cМир не найден!");
                rs.close(); ps.close();
                return;
            }

            Location loc = new Location(world, 
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getFloat("yaw"), rs.getFloat("pitch")
            );
            rs.close(); ps.close();

            msg(player, "&aТелепортация...");
            player.teleport(loc);

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void cmdSetHome(Player player, String[] args) {
        String name = args.length > 0 ? args[0].toLowerCase() : "home";
        UUID uuid = player.getUniqueId();
        Location loc = player.getLocation();

        int maxHomes = getConfig().getInt("homes.max-homes", 3);
        int count = getHomesCount(uuid);

        if (count >= maxHomes && !homeExists(uuid, name)) {
            msg(player, "&cМаксимум домов: &e" + maxHomes);
            return;
        }

        try {
            PreparedStatement ps = db.prepareStatement(
                "INSERT OR REPLACE INTO homes (uuid, name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            );
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, loc.getWorld().getName());
            ps.setDouble(4, loc.getX());
            ps.setDouble(5, loc.getY());
            ps.setDouble(6, loc.getZ());
            ps.setFloat(7, loc.getYaw());
            ps.setFloat(8, loc.getPitch());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        msg(player, "&a✓ Дом '&e" + name + "&a' установлен!");
    }

    private void cmdDelHome(Player player, String[] args) {
        if (args.length < 1) {
            msg(player, "&cИспользование: /delhome <название>");
            return;
        }

        String name = args[0].toLowerCase();
        UUID uuid = player.getUniqueId();

        if (!homeExists(uuid, name)) {
            msg(player, "&cДом '&e" + name + "&c' не найден!");
            return;
        }

        try {
            PreparedStatement ps = db.prepareStatement("DELETE FROM homes WHERE uuid = ? AND name = ?");
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        msg(player, "&a✓ Дом '&e" + name + "&a' удалён!");
    }

    private void cmdHomes(Player player) {
        UUID uuid = player.getUniqueId();
        List<String> homes = new ArrayList<>();

        try {
            PreparedStatement ps = db.prepareStatement("SELECT name FROM homes WHERE uuid = ?");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                homes.add(rs.getString("name"));
            }
            rs.close(); ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        if (homes.isEmpty()) {
            msg(player, "&eУ вас нет домов. Используйте &a/sethome");
            return;
        }

        int max = getConfig().getInt("homes.max-homes", 3);
        msg(player, "&b&lВаши дома &7(" + homes.size() + "/" + max + ")&b:");
        for (String home : homes) {
            msg(player, "&8 • &a" + home);
        }
    }

    private void cmdSpawn(Player player) {
        try {
            PreparedStatement ps = db.prepareStatement("SELECT * FROM spawn WHERE id = 1");
            ResultSet rs = ps.executeQuery();

            if (!rs.next()) {
                msg(player, "&cСпавн не установлен!");
                rs.close(); ps.close();
                return;
            }

            World world = Bukkit.getWorld(rs.getString("world"));
            if (world == null) {
                msg(player, "&cМир не найден!");
                rs.close(); ps.close();
                return;
            }

            Location loc = new Location(world,
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getFloat("yaw"), rs.getFloat("pitch")
            );
            rs.close(); ps.close();

            msg(player, "&aТелепортация на спавн...");
            player.teleport(loc);

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void cmdSetSpawn(Player player) {
        if (!player.hasPermission("plasma.admin")) {
            msg(player, "&cНет прав!");
            return;
        }

        Location loc = player.getLocation();
        try {
            PreparedStatement ps = db.prepareStatement(
                "INSERT OR REPLACE INTO spawn (id, world, x, y, z, yaw, pitch) VALUES (1, ?, ?, ?, ?, ?, ?)"
            );
            ps.setString(1, loc.getWorld().getName());
            ps.setDouble(2, loc.getX());
            ps.setDouble(3, loc.getY());
            ps.setDouble(4, loc.getZ());
            ps.setFloat(5, loc.getYaw());
            ps.setFloat(6, loc.getPitch());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        msg(player, "&a✓ Спавн установлен!");
    }

    private void cmdTpa(Player player, String[] args) {
        if (args.length < 1) {
            msg(player, "&cИспользование: /tpa <игрок>");
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            msg(player, "&cИгрок не найден!");
            return;
        }
        if (target.equals(player)) {
            msg(player, "&cНельзя отправить запрос себе!");
            return;
        }

        tpaRequests.put(target.getUniqueId(), player.getUniqueId());
        msg(player, "&a✓ Запрос отправлен игроку &e" + target.getName());
        msg(target, "&e" + player.getName() + " &aхочет телепортироваться к вам!");
        msg(target, "&a/tpaccept &7- принять | &c/tpdeny &7- отклонить");

        // Автоудаление через 60 сек
        UUID targetUUID = target.getUniqueId();
        UUID requesterUUID = player.getUniqueId();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (tpaRequests.containsKey(targetUUID) && tpaRequests.get(targetUUID).equals(requesterUUID)) {
                    tpaRequests.remove(targetUUID);
                }
            }
        }.runTaskLater(this, 60 * 20L);
    }

    private void cmdTpAccept(Player player) {
        UUID targetUUID = player.getUniqueId();

        if (!tpaRequests.containsKey(targetUUID)) {
            msg(player, "&cНет активных запросов!");
            return;
        }

        UUID requesterUUID = tpaRequests.remove(targetUUID);
        Player requester = Bukkit.getPlayer(requesterUUID);

        if (requester == null || !requester.isOnline()) {
            msg(player, "&cИгрок вышел!");
            return;
        }

        requester.teleport(player.getLocation());
        msg(player, "&a✓ Запрос принят!");
        msg(requester, "&a✓ Телепортация!");
    }

    private void cmdTpDeny(Player player) {
        UUID targetUUID = player.getUniqueId();

        if (!tpaRequests.containsKey(targetUUID)) {
            msg(player, "&cНет активных запросов!");
            return;
        }

        UUID requesterUUID = tpaRequests.remove(targetUUID);
        Player requester = Bukkit.getPlayer(requesterUUID);

        msg(player, "&c✗ Запрос отклонён");
        if (requester != null) {
            msg(requester, "&c✗ Ваш запрос отклонён");
        }
    }

    private void cmdBalance(Player player, String[] args) {
        if (args.length > 0 && player.hasPermission("plasma.admin")) {
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                msg(player, "&cИгрок не найден!");
                return;
            }
            msg(player, "&aБаланс &e" + target.getName() + "&a: &e" + getCoins(target.getUniqueId()) + "⛃");
            return;
        }

        msg(player, "&aВаш баланс: &e" + getCoins(player.getUniqueId()) + "⛃");
    }

    private void cmdPay(Player player, String[] args) {
        if (args.length < 2) {
            msg(player, "&cИспользование: /pay <игрок> <сумма>");
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            msg(player, "&cИгрок не найден!");
            return;
        }
        if (target.equals(player)) {
            msg(player, "&cНельзя перевести себе!");
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            msg(player, "&cНеверная сумма!");
            return;
        }

        double balance = getCoins(player.getUniqueId());
        if (balance < amount) {
            msg(player, "&cНедостаточно средств!");
            return;
        }

        setCoins(player.getUniqueId(), balance - amount);
        setCoins(target.getUniqueId(), getCoins(target.getUniqueId()) + amount);

        msg(player, "&a✓ Вы отправили &e" + amount + "⛃ &aигроку &e" + target.getName());
        msg(target, "&a✓ Вы получили &e" + amount + "⛃ &aот &e" + player.getName());
    }

    private void cmdReload(Player player) {
        if (!player.hasPermission("plasma.admin")) {
            msg(player, "&cНет прав!");
            return;
        }
        reloadConfig();
        msg(player, "&a✓ Конфиг перезагружен!");
    }

    // ============================================================
    //                      EVENTS
    // ============================================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Создаём игрока
        createPlayer(uuid, player.getName());

        // Проверяем сессию
        String ip = player.getAddress().getAddress().getHostAddress();
        if (hasValidSession(uuid, ip)) {
            loggedIn.add(uuid);
            msg(player, "&a✓ Автоматический вход!");
            Bukkit.getScheduler().runTaskLater(this, () -> showScoreboard(player), 10L);
            return;
        }

        // Замораживаем
        frozen.add(uuid);
        player.setWalkSpeed(0f);
        player.setFlySpeed(0f);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!loggedIn.contains(uuid)) {
                if (isRegistered(uuid)) {
                    msg(player, "&eВойдите: &a/login <пароль>");
                } else {
                    msg(player, "&eЗарегистрируйтесь: &a/register <пароль>");
                }
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        frozen.remove(uuid);
        playerBoards.remove(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
                event.setTo(from);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!loggedIn.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!loggedIn.contains(event.getPlayer().getUniqueId())) {
            String cmd = event.getMessage().toLowerCase();
            if (!cmd.startsWith("/login") && !cmd.startsWith("/register") &&
                !cmd.startsWith("/l ") && !cmd.startsWith("/reg ")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!loggedIn.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!loggedIn.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!loggedIn.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (!loggedIn.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ============================================================
    //                      SCOREBOARD & HUD & TAB
    // ============================================================

    private void startTasks() {
        // Scoreboard + HUD update
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (loggedIn.contains(player.getUniqueId())) {
                        updateScoreboard(player);
                        updateHUD(player);
                        updateTab(player);
                    }
                }
            }
        }.runTaskTimer(this, 40L, 20L);
    }

    private void showScoreboard(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective("plasma", Criteria.DUMMY, color("&b&lPLASMA"));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        playerBoards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
        updateScoreboard(player);
    }

    private void updateScoreboard(Player player) {
        Scoreboard board = playerBoards.get(player.getUniqueId());
        if (board == null) return;

        Objective obj = board.getObjective("plasma");
        if (obj == null) return;

        // Очищаем
        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }

        double coins = getCoins(player.getUniqueId());
        int online = Bukkit.getOnlinePlayers().size();
        int x = player.getLocation().getBlockX();
        int y = player.getLocation().getBlockY();
        int z = player.getLocation().getBlockZ();

        String[] lines = {
            "&8&m─────────────",
            "&fИгрок: &a" + player.getName(),
            "&fБаланс: &e" + (int)coins + "⛃",
            " ",
            "&fОнлайн: &a" + online,
            "&fКоорды: &7" + x + " " + y + " " + z,
            "&8&m─────────────&r",
            "&bplasma.mc.20tps.monster"
        };

        int score = lines.length;
        for (String line : lines) {
            String colored = colorString(line);
            while (board.getEntries().contains(colored)) {
                colored += "§r";
            }
            obj.getScore(colored).setScore(score--);
        }
    }

    private void updateHUD(Player player) {
        double coins = getCoins(player.getUniqueId());
        double health = player.getHealth();
        String hud = colorString("&c❤ " + (int)health + " &7| &e" + (int)coins + "⛃ &7| &a" + Bukkit.getOnlinePlayers().size() + " онлайн");
        player.sendActionBar(Component.text(hud));
    }

    private void updateTab(Player player) {
        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        
        player.sendPlayerListHeaderAndFooter(
            color("\n&b&lPLASMA SERVER\n&7Добро пожаловать!\n"),
            color("\n&7Онлайн: &a" + online + "&7/&a" + max + "\n")
        );
    }

    // ============================================================
    //                      HELPERS
    // ============================================================

    private void login(Player player) {
        UUID uuid = player.getUniqueId();
        loggedIn.add(uuid);
        frozen.remove(uuid);
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);

        // Сессия
        String ip = player.getAddress().getAddress().getHostAddress();
        int minutes = getConfig().getInt("auth.session-minutes", 60);
        long expires = System.currentTimeMillis() + (minutes * 60 * 1000L);
        createSession(uuid, ip, expires);

        showScoreboard(player);
    }

    private void msg(Player player, String message) {
        player.sendMessage(color("&b&lPLASMA &8» &r" + message));
    }

    private Component color(String msg) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(msg);
    }

    private String colorString(String msg) {
        return msg.replace("&", "§");
    }

    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return password;
        }
    }

    // ============================================================
    //                      DATABASE HELPERS
    // ============================================================

    private void createPlayer(UUID uuid, String name) {
        try {
            PreparedStatement ps = db.prepareStatement(
                "INSERT OR IGNORE INTO players (uuid, name, coins) VALUES (?, ?, ?)"
            );
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setDouble(3, getConfig().getDouble("coins.start-balance", 100));
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private boolean isRegistered(UUID uuid) {
        try {
            PreparedStatement ps = db.prepareStatement("SELECT password FROM players WHERE uuid = ? AND password IS NOT NULL");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            boolean reg = rs.next();
            rs.close(); ps.close();
            return reg;
        } catch (SQLException e) {
            return false;
        }
    }

    private String getPassword(UUID uuid) {
        try {
            PreparedStatement ps = db.prepareStatement("SELECT password FROM players WHERE uuid = ?");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            String pass = rs.next() ? rs.getString("password") : null;
            rs.close(); ps.close();
            return pass;
        } catch (SQLException e) {
            return null;
        }
    }

    private double getCoins(UUID uuid) {
        try {
            PreparedStatement ps = db.prepareStatement("SELECT coins FROM players WHERE uuid = ?");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            double coins = rs.next() ? rs.getDouble("coins") : 0;
            rs.close(); ps.close();
            return coins;
        } catch (SQLException e) {
            return 0;
        }
    }

    private void setCoins(UUID uuid, double amount) {
        try {
            PreparedStatement ps = db.prepareStatement("UPDATE players SET coins = ? WHERE uuid = ?");
            ps.setDouble(1, amount);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private int getHomesCount(UUID uuid) {
        try {
            PreparedStatement ps = db.prepareStatement("SELECT COUNT(*) as cnt FROM homes WHERE uuid = ?");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            int cnt = rs.next() ? rs.getInt("cnt") : 0;
            rs.close(); ps.close();
            return cnt;
        } catch (SQLException e) {
            return 0;
        }
    }

    private boolean homeExists(UUID uuid, String name) {
        try {
            PreparedStatement ps = db.prepareStatement("SELECT id FROM homes WHERE uuid = ? AND name = ?");
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ResultSet rs = ps.executeQuery();
            boolean exists = rs.next();
            rs.close(); ps.close();
            return exists;
        } catch (SQLException e) {
            return false;
        }
    }

    private void createSession(UUID uuid, String ip, long expires) {
        try {
            PreparedStatement ps = db.prepareStatement(
                "INSERT OR REPLACE INTO sessions (uuid, ip, expires) VALUES (?, ?, ?)"
            );
            ps.setString(1, uuid.toString());
            ps.setString(2, ip);
            ps.setLong(3, expires);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private boolean hasValidSession(UUID uuid, String ip) {
        try {
            PreparedStatement ps = db.prepareStatement("SELECT expires FROM sessions WHERE uuid = ? AND ip = ?");
            ps.setString(1, uuid.toString());
            ps.setString(2, ip);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                boolean valid = System.currentTimeMillis() < rs.getLong("expires");
                rs.close(); ps.close();
                return valid;
            }
            rs.close(); ps.close();
            return false;
        } catch (SQLException e) {
            return false;
        }
    }

    public static PlasmaCore getInstance() {
        return instance;
    }
}
