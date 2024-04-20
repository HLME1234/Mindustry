package mindustry.server;

import arc.*;
import arc.files.*;
import arc.func.Cons;
import arc.struct.*;
import arc.util.*;
import arc.util.Timer;
import arc.util.CommandHandler.*;
import arc.util.Timer.*;
import arc.util.serialization.*;
import arc.util.serialization.JsonValue.*;
import mindustry.core.GameState.*;
import mindustry.core.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.maps.Map;
import mindustry.maps.*;
import mindustry.maps.Maps.*;
import mindustry.mod.Mods.*;
import mindustry.net.Administration.*;
import mindustry.net.Packets.*;
import mindustry.net.*;
import mindustry.type.*;

import java.io.*;
import java.net.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

import static arc.util.ColorCodes.*;
import static arc.util.Log.*;
import static mindustry.Vars.*;

public class ServerControl implements ApplicationListener{
    protected static String[] tags = {"&lc&fb[D]&fr", "&lb&fb[I]&fr", "&ly&fb[W]&fr", "&lr&fb[E]", ""};
    protected static DateTimeFormatter dateTime = DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm:ss"),
        autosaveDate = DateTimeFormatter.ofPattern("MM-dd-yyyy_HH-mm-ss");

    /** Global instance of ServerControl, initialized when the server is created. Should never be null on a dedicated server. */
    public static ServerControl instance;

    public final CommandHandler handler = new CommandHandler("");
    public final Fi logFolder = Core.settings.getDataDirectory().child("logs/");

    private final Interval autosaveCount = new Interval();

    public Runnable serverInput = () -> {
        Scanner scan = new Scanner(System.in);
        while(scan.hasNext()){
            String line = scan.nextLine();
            Core.app.post(() -> handleCommandString(line));
        }
    };

    /** The file to which the logs are currently being written. */
    public Fi currentLogFile;

    /** Whether the server is currently waiting for the next map to be loaded. */
    public boolean inGameOverWait;

    /** The last gamemode loaded on this server. */
    public Gamemode lastMode;

    private Task lastTask;
    private Thread socketThread;
    private ServerSocket serverSocket;
    private PrintWriter socketOutput;
    private String suggested;
    private boolean autoPaused = false;

    public Cons<GameOverEvent> gameOverListener = event -> {
        if(state.rules.waves){
            info("游戏结束！已到达第 @ 波，@ 名玩家在线，地图为 @。", state.wave, Groups.player.size(), Strings.capitalize(state.map.plainName()));
        }else{
            info("游戏结束！队伍 @ 获胜，@ 名玩家在线，地图为 @。", event.winner.name, Groups.player.size(), Strings.capitalize(state.map.plainName()));
        }

        //set the next map to be played
        Map map = maps.getNextMap(lastMode, state.map);
        if(map != null){
            Call.infoMessage((state.rules.pvp
                    ? "[accent]队伍 " + event.winner.coloredName() + " 获胜![]\n" : "[scarlet]游戏结束![]\n")
                    + "\n下一张选中的地图: [accent]" + map.name() + "[white]"
                    + (map.hasTag("author") ? " 制作者[accent] " + map.author() + "[white]" : "") + "." +
                    "\n新游戏将在" + Config.roundExtraTime.num() + "秒后开始。");
            state.gameOver = true;
            Call.updateGameOver(event.winner);

            info("已选择下一张地图：@。", map.plainName());

            play(() -> world.loadMap(map, map.applyRules(lastMode)));
        }else{
            netServer.kickAll(KickReason.gameover);
            state.set(State.menu);
            net.closeServer();
        }
    };

    public ServerControl(String[] args){
        setup(args);
        instance = this;
    }

    protected void setup(String[] args){
        Core.settings.defaults(
            "bans", "",
            "admins", "",
            "shufflemode", "custom",
            "globalrules", "{reactorExplosions: false, logicUnitBuild: false}"
        );

        //update log level
        Config.debug.set(Config.debug.bool());

        try{
            lastMode = Gamemode.valueOf(Core.settings.getString("lastServerMode", "survival"));
        }catch(Exception e){ //handle enum parse exception
            lastMode = Gamemode.survival;
        }

        logger = (level1, text) -> {
            //err has red text instead of reset.
            if(level1 == LogLevel.err) text = text.replace(reset, lightRed + bold);

            String result = bold + lightBlack + "[" + dateTime.format(LocalDateTime.now()) + "] " + reset + format(tags[level1.ordinal()] + " " + text + "&fr");
            System.out.println(result);

            if(Config.logging.bool()){
                logToFile("[" + dateTime.format(LocalDateTime.now()) + "] " + formatColors(tags[level1.ordinal()] + " " + text + "&fr", false));
            }

            if(socketOutput != null){
                try{
                    socketOutput.println(formatColors(text + "&fr", false));
                }catch(Throwable e1){
                    err("连接至套接字时发生错误：@", e1.getClass().getSimpleName());
                }
            }
        };

        formatter = (text, useColors, arg) -> {
            text = Strings.format(text.replace("@", "&fb&lb@&fr"), arg);
            return useColors ? addColors(text) : removeColors(text);
        };

        Time.setDeltaProvider(() -> Core.graphics.getDeltaTime() * 60f);

        registerCommands();

        Core.app.post(() -> {
            //try to load auto-update save if possible
            if(Config.autoUpdate.bool()){
                Fi fi = saveDirectory.child("autosavebe." + saveExtension);
                if(fi.exists()){
                    try{
                        SaveIO.load(fi);
                        info("自动保存已加载。");
                        state.set(State.playing);
                        netServer.openServer();
                    }catch(Throwable e){
                        err(e);
                    }
                }
            }

            Seq<String> commands = new Seq<>();

            if(args.length > 0){
                commands.addAll(Strings.join(" ", args).split(","));
                info("找到 @ 个命令行参数待解析。", commands.size);
            }

            if(!Config.startCommands.string().isEmpty()){
                String[] startup = Strings.join(" ", Config.startCommands.string()).split(",");
                info("找到 @ 个启动命令。", startup.length);
                commands.addAll(startup);
            }

            for(String s : commands){
                CommandResponse response = handler.handleMessage(s);
                if(response.type != ResponseType.valid){
                    err("发送了无效的命令参数：'@'：@", s, response.type.name());
                    err("参数用法：&lb<command-1> <command1-args...>,<command-2> <command-2-args2...>");
                }
            }
        });

        customMapDirectory.mkdirs();

        if(Version.build == -1){
            warn("&ly您的服务器正在运行自定义构建，这意味着客户端检查已被禁用。");
            warn("&ly强烈建议通过使用 gradle 参数 &lb&fb-Pbuildversion=&lr<build> 指定您所使用的版本。");
        }

        //set up default shuffle mode
        try{
            maps.setShuffleMode(ShuffleMode.valueOf(Core.settings.getString("shufflemode")));
        }catch(Exception e){
            maps.setShuffleMode(ShuffleMode.all);
        }

        Events.on(GameOverEvent.class, event -> {
            if(!inGameOverWait && gameOverListener != null){
                gameOverListener.get(event);
            }
        });

        //reset autosave on world load
        Events.on(WorldLoadEvent.class, e -> {
            autosaveCount.reset(0, Config.autosaveSpacing.num() * 60);
        });

        //autosave periodically
        Events.run(Trigger.update, () -> {
            if(state.isPlaying() && Config.autosave.bool()){
                if(autosaveCount.get(Config.autosaveSpacing.num() * 60)){
                    int max = Config.autosaveAmount.num();

                    //use map file name to make sure it can be saved
                    String mapName = (state.map.file == null ? "unknown" : state.map.file.nameWithoutExtension()).replace(" ", "_");
                    String date = autosaveDate.format(LocalDateTime.now());

                    Seq<Fi> autosaves = saveDirectory.findAll(f -> f.name().startsWith("auto_"));
                    autosaves.sort(f -> -f.lastModified());

                    //delete older saves
                    if(autosaves.size >= max){
                        for(int i = max - 1; i < autosaves.size; i++){
                            autosaves.get(i).delete();
                        }
                    }

                    String fileName = "auto_" + mapName + "_" + date + "." + saveExtension;
                    Fi file = saveDirectory.child(fileName);
                    info("正在进行自动保存...");

                    try{
                        SaveIO.save(file);
                        info("自动保存已完成。");
                    }catch(Throwable e){
                        err("自动保存失败。", e);
                    }
                }
            }
        });

        Events.run(Trigger.socketConfigChanged, () -> {
            toggleSocket(false);
            toggleSocket(Config.socketInput.bool());
        });

        Events.on(PlayEvent.class, e -> {
            try{
                JsonValue value = JsonIO.json.fromJson(null, Core.settings.getString("globalrules"));
                JsonIO.json.readFields(state.rules, value);
            }catch(Throwable t){
                err("应用自定义规则时出错，将不使用它们继续。", t);
            }
        });

        //autosave settings once a minute
        float saveInterval = 60;
        Timer.schedule(() -> {
            netServer.admins.forceSave();
            Core.settings.forceSave();
        }, saveInterval, saveInterval);

        if(!mods.orderedMods().isEmpty()){
            info("@ 个模组已加载。", mods.orderedMods().size);
        }

        int unsupported = mods.list().count(l -> !l.enabled());

        if(unsupported > 0){
            Log.err("加载 @ 个模组时出现错误。"):", unsupported);
            for(LoadedMod mod : mods.list().select(l -> !l.enabled())){
                Log.err("- @ &ly(" + mod.state + ")", mod.meta.name);
            }
        }

        toggleSocket(Config.socketInput.bool());

        Events.on(ServerLoadEvent.class, e -> {
            if(serverInput != null){
                Thread thread = new Thread(serverInput, "Server Controls");
                thread.setDaemon(true);
                thread.start();
            }

            info("服务器已加载。输入 @ 获取帮助。", "'help'");
        });

        Events.on(SaveLoadEvent.class, e -> {
            Core.app.post(() -> {
                if(Config.autoPause.bool() && Groups.player.size() == 0){
                    state.set(State.paused);
                    autoPaused = true;
                }
            });
        });

        Events.on(PlayerJoin.class, e -> {
            if(state.isPaused() && autoPaused && Config.autoPause.bool()){
                state.set(State.playing);
                autoPaused = false;
            }
        });

        Events.on(PlayerLeave.class, e -> {
            // The player list length is compared with 1 and not 0 here,
            // because when PlayerLeave gets fired, the player hasn't been removed from the player list yet
            if(!state.isPaused() && Config.autoPause.bool() && Groups.player.size() == 1){
                state.set(State.paused);
                autoPaused = true;
            }
        });
    }

    protected void registerCommands(){
        handler.register("help", "[命令]", "显示命令列表，或获取特定命令的帮助", arg -> {
            if(arg.length > 0){
                Command command = handler.getCommandList().find(c -> c.text.equalsIgnoreCase(arg[0]));
                if(command == null){
                    err("未找到命令 " + arg[0] + "！");
                }else{
                    info(command.text + ":");
                    info("  &b&lb}" + command.text + (command.paramText.isEmpty() ? "" : " &lc&fi") + command.paramText + "&fr - &lw" + command.description);
                }
            }else{
                info("命令：");
                for(Command command : handler.getCommandList()){
                    info("  &b&lb}" + command.text + (command.paramText.isEmpty() ? "" : " &lc&fi") + command.paramText + "&fr - &lw" + command.description);
                }
            }
        });

        handler.register("version", "显示服务器版本信息", arg -> {
            info("版本：Mindustry @-@ @ / 构建 @", Version.number, Version.modifier, Version.type, Version.build + (Version.revision == 0 ? "" : "." + Version.revision));
            info("Java 版本：@", OS.javaVersion);
        });

        handler.register("exit", "退出服务器程序", arg -> {
            info("正在关闭服务器。");
            net.dispose();
            Core.app.exit();
        });

        handler.register("stop", "停止托管服务器", arg -> {
            net.closeServer();
            cancelPlayTask();
            state.set(State.menu);
            info("已停止系统。");
        });

        handler.register("host", "[地图名] [模式]", "启动服务器。如果未指定将使用随机地图与生存模式", arg -> {
            if(state.isGame()){
                err("已主持。键入 'stop' 以停止主持。");
                return;
            }

            cancelPlayTask();

            Gamemode preset = Gamemode.survival;

            if(arg.length > 1){
                try{
                    preset = Gamemode.valueOf(arg[1]);
                }catch(IllegalArgumentException e){
                    err("找不到游戏模式 '@'。", arg[1]);
                    return;
                }
            }

            Map result;
            if(arg.length > 0){
                result = maps.all().find(map -> map.plainName().replace('_', ' ').equalsIgnoreCase(Strings.stripColors(arg[0]).replace('_', ' ')));

                if(result == null){
                    err("找不到名为 '@' 的地图。", arg[0]);
                    return;
                }
            }else{
                result = maps.getShuffleMode().next(preset, state.map);
                info("已随机选择下一张地图为 @。", system.plainName());
            }

            info("正在加载地图...");

            logic.reset();
            lastMode = preset;
            Core.settings.put("lastServerMode", lastMode.name());
            try{
                world.loadMap(result, result.applyRules(lastMode));
                state.rules = result.applyRules(preset);
                logic.play();

                info("地图已加载。");

                netServer.openServer();

                if(Config.autoPause.bool()){
                    state.set(State.paused);
                    autoPaused = true;
                }
            }catch(MapException e){
                err("@: @", e.map.plainName())), e.getMessage());
            }
        });

        handler.register("maps", "[all/custom/default]", "显示可用地图。默认仅显示自定义地图。", arg -> {
            boolean custom = arg.length == 0 || arg[0].equals("custom") || arg[0].equals("all");
            boolean def = arg.length > 0 && (arg[0].equals("default") || arg[0].equals("all"));

            if(!maps.all().isEmpty()){
                Seq<Map> all = new Seq<>();

                if(custom) all.addAll(maps.customMaps());
                if(def) all.addAll(maps.defaultMaps());

                if(all.isEmpty()){
                    info("未加载任何自定义地图。&fi要显示内置地图，请使用 \"@\" 参数。", "all");
                }else{
                    info("地图：");

                    for(Map map : all){
                        String mapName = map.plainName().replace(' ', '_');
                        if(map.custom){
                            info("  @ (@): &fiCustom / @x@", mapName, map.file.name(), map.width, map.height);
                        }else{
                            info("  @: &fiDefault / @x@", mapName, map.width, map.height);
                        }
                    }
                }
            }else{
                info("没有找到地图。");
            }
            info("地图目录：&fi@", customMapDirectory.file()).getAbsoluteFile().toString());
        });

        handler.register("reloadmaps", "从硬盘中重载所有地图", arg -> {
            int beforeMaps = maps.all().size;
            maps.reload();
            if(maps.all().size > beforeMaps){
                info("@ 张新地图") found and reloaded.", maps.all().size - beforeMaps);
            }else if(maps.all().size < beforeMaps){
                info("@ 张旧地图") deleted.", beforeMaps - maps.all().size);
            }else{
                info("地图已重新加载。");
            }
        });

        handler.register("status", "显示服务器状态", arg -> {
            if(state.isMenu()){
                info("状态：&r服务器关闭");
            }else{
                info("状态：");
                info("  在地图 &fi@ / 第 @ 波玩游戏", Strings.capitalize(state.map.plainName())), state.wave);

                if(state.rules.waves){
                    info("  还有 @ 秒到下一波。", (int)(state.wavetime / 60));
                }
                info("  @ 个单位 / @ 个敌人", Groups.unit.size()), state.enemies);

                info("  @ 帧/秒，@ MB 已使用。", Core.graphics.getFramesPerSecond(), Core.app.getJavaHeap() / 1024 / 1024);

                if(Groups.player.size() > 0){
                    info("  玩家：@", Groups.player.size()));
                    for(Player p : Groups.player){
                        info("    @ @ / @", p.admin())) ? "&r[A]&c" : "&b[P]&c", p.plainName(), p.uuid());
                    }
                }else{
                    info("  无玩家连接。");
                }
            }
        });

        handler.register("mods", "显示所有已加载模组", arg -> {
            if(!mods.list().isEmpty()){
                info("模组：");
                for(LoadedMod mod : mods.list()){
                    info("  @ &fi@ " + (mod.enabled())) ? "" : " &lr(" + mod.state + ")"), mod.meta.displayName, mod.meta.version);
                }
            }else{
                info("未找到模组。");
            }
            info("模组目录：&fi@", modDirectory.file()).getAbsoluteFile().toString());
        });

        handler.register("mod", "<名称...>", "显示已加载插件的信息", arg -> {
            LoadedMod mod = mods.list().find(p -> p.meta.name.equalsIgnoreCase(arg[0]));
            if(mod != null){
                info("名称：@", mod.meta.displayName);
                info("内部名称：@", mod.name);
                info("版本：@", mod.meta.version);
                info("作者：@", mod.meta.author);
                info("路径：@", mod.file.path()));
                info("描述：@", mod.meta.description);
            }else{
                info("未找到名为 '@' 的模组。", arg[0]);
            }
        });

        handler.register("js", "<脚本...>", "执行任意JS", arg -> {
            info("&fi&lw&fb" + mods.getScripts())).runConsole(arg[0]));
        });

        handler.register("say", "<消息...>", "对所有玩家发送消息", arg -> {
            if(!state.isGame()){
                err("未主持。首先主持游戏。");
                return;
            }

            Call.sendMessage("[scarlet][[服务器]:[] " + arg[0]);

            info("&fi&lc服务器：&fr@", "&lw" + arg[0]);
        });

        handler.register("pause", "<on/off>", "控制游戏暂停", arg -> {
            if(state.isMenu()){
                err("没有游戏运行时无法暂停。");
                return;
            }
            boolean pause = arg[0].equals("on");
            autoPaused = false;
            state.set(pause ? State.paused : State.playing);
            info(pause ? "游戏已暂停。" : "游戏已取消暂停。");
        });

        handler.register("rules", "[remove/add] [name] [value...]", "List, remove or add global rules. These will apply regardless of map.", arg -> {
            String rules = Core.settings.getString("globalrules");
            JsonValue base = JsonIO.json.fromJson(null, rules);

            if(arg.length == 0){
                info("规则：
@", JsonIO.print(rules)));
            }else if(arg.length == 1){
                err("使用不当。指定要移除或添加的规则。");
            }else{
                if(!(arg[0].equals("remove") || arg[0].equals("add"))){
                    err("使用不当。要么添加规则，要么移除规则。");
                    return;
                }

                boolean remove = arg[0].equals("remove");
                if(remove){
                    if(base.has(arg[1])){
                        info("已移除规则 '@'", arg[1]);
                        base.remove(arg[1]);
                    }else{
                        err("未定义规则，因此未更新。");
                        return;
                    }
                }else{
                    if(arg.length < 3){
                        err("缺少最后一个参数。指定要设置规则的值。");
                        return;
                    }

                    try{
                        JsonValue value = new JsonReader().parse(arg[2]);
                        value.name = arg[1];

                        JsonValue parent = new JsonValue(ValueType.object);
                        parent.addChild(value);

                        JsonIO.json.readField(state.rules, value.name, parent);
                        if(base.has(value.name)){
                            base.remove(value.name);
                        }
                        base.addChild(arg[1], value);
                        info("更改的规则：@", value.toString()).replace("\n", " "));
                    }catch(Throwable e){
                        err("解析规则 JSON 时出错：@", e.getMessage()));
                    }
                }

                Core.settings.put("globalrules", base.toString());
                Call.setRules(state.rules);
            }
        });

        handler.register("fillitems", "[队伍]", "填充队伍核心", arg -> {
            if(!state.isGame()){
                err("未玩游戏。先主持。");
                return;
            }

            Team team = arg.length == 0 ? Team.sharded : Structs.find(Team.all, t -> t.name.equals(arg[0]));

            if(team == null){
                err("未找到该名称的队伍。");
                return;
            }

            if(state.teams.cores(team).isEmpty()){
                err("该队伍无核心。");
                return;
            }

            for(Item item : content.items()){
                state.teams.cores(team).first().items.set(item, state.teams.cores(team).first().storageCapacity);
            }

            info("核心已填满。");
        });

        handler.register("playerlimit", "[off/somenumber]", "设置服务器玩家限制", arg -> {
            if(arg.length == 0){
                info("当前玩家限制为 @。", netServer.admins.getPlayerLimit()) == 0 ? "off" : netServer.admins.getPlayerLimit());
                return;
            }
            if(arg[0].equals("off")){
                netServer.admins.setPlayerLimit(0);
                info("已禁用玩家限制。");
                return;
            }

            if(Strings.canParsePositiveInt(arg[0]) && Strings.parseInt(arg[0]) > 0){
                int lim = Strings.parseInt(arg[0]);
                netServer.admins.setPlayerLimit(lim);
                info("玩家限制现在是 &lc@。", lim);
            }else{
                err("限制必须为大于 0 的数字。");
            }
        });

        handler.register("config", "[名称] [值...]", "设置服务器配置", arg -> {
            if(arg.length == 0){
                info("所有配置值：");
                for(Config c : Config.all){
                    info("&lk| @: @", c.name, "&lc&fi" + c.get())));
                    info("&lk| | &lw" + c.description);
                    info("&lk|");
                }
                return;
            }

            Config c = Config.all.find(conf -> conf.name.equalsIgnoreCase(arg[0]));

            if(c != null){
                if(arg.length == 1){
                    info("'@' 当前为 @。", c.name, c.get()));
                }else{
                    if(arg[1].equals("default")){
                        c.set(c.defaultValue);
                    }else if(c.isBool()){
                        c.set(arg[1].equals("on") || arg[1].equals("true"));
                    }else if(c.isNum()){
                        try{
                            c.set(Integer.parseInt(arg[1]));
                        }catch(NumberFormatException e){
                            err("不是一个有效的数字：@", arg[1]);
                            return;
                        }
                    }else if(c.isString()){
                        c.set(arg[1].replace("\\n", "\n"));
                    }

                    info("@ 设置为 @。", c.name, c.get()));
                    Core.settings.forceSave();
                }
            }else{
                err("未知配置：'@'。使用不带参数的命令以获取有效配置列表。", arg[0]);
            }
        });

        handler.register("subnet-ban", "[add/remove] [address]", "禁止一个子网。这将简单地拒绝所有以某一字符串开头的IP地址的连接。", arg -> {
            if(arg.length == 0){
                info("子网被禁止：@", netServer.admins.getSubnetBans()).isEmpty() ? "<none>" : "");
                for(String subnet : netServer.admins.getSubnetBans()){
                    info("&lw  " + subnet);
                }
            }else if(arg.length == 1){
                err("您必须提供一个子网以添加或移除。");
            }else{
                if(arg[0].equals("add")){
                    if(netServer.admins.getSubnetBans().contains(arg[1])){
                        err("该子网已禁止。");
                        return;
                    }

                    netServer.admins.addSubnetBan(arg[1]);
                    info("已禁止 @**", arg[1]);
                }else if(arg[0].equals("remove")){
                    if(!netServer.admins.getSubnetBans().contains(arg[1])){
                        err("该子网未被禁止。");
                        return;
                    }

                    netServer.admins.removeSubnetBan(arg[1]);
                    info("已解禁 @**", arg[1]);
                }else{
                    err("使用不正确。提供 add/remove 作为第二个参数。");
                }
            }
        });

        handler.register("whitelist", "[add/remove] [ID]", "使用玩家ID将玩家添加/移除到白名单中。", arg -> {
            if(arg.length == 0){
                Seq<PlayerInfo> whitelist = netServer.admins.getWhitelisted();

                if(whitelist.isEmpty()){
                    info("未找到白名单玩家。");
                }else{
                    info("白名单：");
                    whitelist.each(p -> info("- 名称：@ / UUID：@", p.plainLastName()), p.id));
                }
            }else{
                if(arg.length == 2){
                    PlayerInfo info = netServer.admins.getInfoOptional(arg[1]);

                    if(info == null){
                        err("玩家 ID 未找到。您必须使用玩家加入服务器时显示的 ID。");
                    }else{
                        if(arg[0].equals("add")){
                            netServer.admins.whitelist(arg[1]);
                            info("玩家 '@' 已被加入白名单。", info.plainLastName()));
                        }else if(arg[0].equals("remove")){
                            netServer.admins.unwhitelist(arg[1]);
                            info("玩家 '@' 已被移出白名单。", info.plainLastName()));
                        }else{
                            err("使用不正确。提供 add/remove 作为第二个参数。");
                        }
                    }
                }else{
                    err("提供一个 ID 以添加或移除。");
                }
            }
        });

        //TODO should be a config, not a separate command.
        handler.register("shuffle", "[none/all/custom/builtin]", "设置地图洗牌模式。", arg -> {
            if(arg.length == 0){
                info("洗牌模式当前设置为 '@'。", maps.getShuffleMode()));
            }else{
                try{
                    ShuffleMode mode = ShuffleMode.valueOf(arg[0]);
                    Core.settings.put("shufflemode", mode.name());
                    maps.setShuffleMode(mode);
                    info("洗牌模式设置为 '@'。", arg[0]);
                }catch(Exception e){
                    err("无效的洗牌模式。");
                }
            }
        });

        handler.register("nextmap", "<地图名...>", "设置游戏结束后要玩的下一张地图。覆盖洗牌设置。", arg -> {
            Map res = maps.all().find(map -> map.plainName().replace('_', ' ').equalsIgnoreCase(Strings.stripColors(arg[0]).replace('_', ' ')));
            if(res != null){
                maps.setNextMapOverride(res);
                info("下一地图设置为 '@'。", res.plainName()));
            }else{
                err("未找到地图 '@'。", arg[0]);
            }
        });

        handler.register("kick", "<玩家名...>", "用名称踢出某玩家", arg -> {
            if(!state.isGame()){
                err("尚未主持游戏。冷静下来。");
                return;
            }

            Player target = Groups.player.find(p -> p.name().equals(arg[0]));

            if(target != null){
                Call.sendMessage("[scarlet]" + target.name() + "[scarlet] 被踢出服务器");
                target.kick(KickReason.kick);
                info("完成。");
            }else{
                info("未找到该名字的人...");
            }
        });

        handler.register("ban", "<type-id/name/ip> <username/IP/ID...>", "Ban某人", arg -> {
            if(arg[0].equals("id")){
                netServer.admins.banPlayerID(arg[1]);
                info("已禁止。");
            }else if(arg[0].equals("name")){
                Player target = Groups.player.find(p -> p.name().equalsIgnoreCase(arg[1]));
                if(target != null){
                    netServer.admins.banPlayer(target.uuid());
                    info("已禁止。");
                }else{
                    err("未找到匹配项。");
                }
            }else if(arg[0].equals("ip")){
                netServer.admins.banPlayerIP(arg[1]);
                info("已禁止。");
            }else{
                err("无效类型。");
            }

            for(Player player : Groups.player){
                if(netServer.admins.isIDBanned(player.uuid())){
                    Call.sendMessage("[scarlet]" + player.name + " 被服务器Ban出");
                    player.con.kick(KickReason.banned);
                }
            }
        });

        handler.register("bans", "列出所有被ban ip或id", arg -> {
            Seq<PlayerInfo> bans = netServer.admins.getBanned();

            if(bans.size == 0){
                info("未找到 ID 禁止的玩家。");
            }else{
                info("已禁止玩家 [ID]：");
                for(PlayerInfo info : bans){
                    info(" @ / 最后已知名字：'@'", info.id, info.plainLastName()));
                }
            }

            Seq<String> ipbans = netServer.admins.getBannedIPs();

            if(ipbans.size == 0){
                info("未找到 IP 禁止的玩家。");
            }else{
                info("已禁止玩家 [IP]：");
                for(String string : ipbans){
                    PlayerInfo info = netServer.admins.findByIP(string);
                    if(info != null){
                        info("  '@' / 最后已知名字：'@' / ID：'@'", string, info.plainLastName()), info.id);
                    }else{
                        info("  '@' (无已知名字或信息)")", string);
                    }
                }
            }
        });

        handler.register("unban", "<ip/ID>", "取消Ban", arg -> {
            if(netServer.admins.unbanPlayerIP(arg[0]) || netServer.admins.unbanPlayerID(arg[0])){
                info("已解禁玩家：@", arg[0]);
            }else{
                err("该 IP/ID 未被禁止！");
            }
        });

        handler.register("pardon", "<ID>", "通过ID赦免被投票踢出的玩家，允许其再次加入。", arg -> {
            PlayerInfo info = netServer.admins.getInfoOptional(arg[0]);

            if(info != null){
                info.lastKicked = 0;
                netServer.admins.kickedIPs.remove(info.lastIP);
                info("已赦免玩家：@", info.plainLastName()));
            }else{
                err("无法找到该 ID。");
            }
        });

        handler.register("admin", "<add/remove> <username/ID...>", "添加服务器管理员", arg -> {
            if(!state.isGame()){
                err("首先打开服务器。");
                return;
            }

            if(!(arg[0].equals("add") || arg[0].equals("remove"))){
                err("第二个参数必须是 'add' 或 'remove'。");
                return;
            }

            boolean add = arg[0].equals("add");

            PlayerInfo target;
            Player playert = Groups.player.find(p -> p.plainName().equalsIgnoreCase(Strings.stripColors(arg[1])));
            if(playert != null){
                target = playert.getInfo();
            }else{
                target = netServer.admins.getInfoOptional(arg[1]);
                playert = Groups.player.find(p -> p.getInfo() == target);
            }

            if(target != null){
                if(add){
                    netServer.admins.adminPlayer(target.id, playert == null ? target.adminUsid : playert.usid());
                }else{
                    netServer.admins.unAdminPlayer(target.id);
                }
                if(playert != null) playert.admin = add;
                info("更改了玩家的管理员状态：@", target.plainLastName()));
            }else{
                err("未找到具有该名字或 ID 的人。如果按名字添加管理员，请确保他们在线；否则，使用其 UUID。");
            }
        });

        handler.register("admins", "列出管理员", arg -> {
            Seq<PlayerInfo> admins = netServer.admins.getAdmins();

            if(admins.size == 0){
                info("未找到管理员。");
            }else{
                info("管理员：");
                for(PlayerInfo info : admins){
                    info(" &lm @ / ID：'@' / IP：'@'", info.plainLastName()), info.id, info.lastIP);
                }
            }
        });

        handler.register("players", "列出当前游戏中所有玩家。", arg -> {
            if(Groups.player.size() == 0){
                info("当前没有玩家在服务器上。");
            }else{
                info("玩家：@", Groups.player.size()));
                for(Player user : Groups.player){
                    info(" @&lm @ / ID：@ / IP：@", user.admin ? "&r[A]&c" : "&b[P]&c", user.plainName()), user.uuid(), user.ip());
                }
            }
        });

        handler.register("runwave", "触发下一波。", arg -> {
            if(!state.isGame()){
                err("未主持。首先主持游戏。");
            }else{
                logic.runWave();
                info("生成波次。");
            }
        });

        handler.register("loadautosave", "加载上一次自动保存。", arg -> {
            if(state.isGame()){
                err("已主持。键入 'stop' 以停止主持。");
                return;
            }

            Fi newestSave = saveDirectory.findAll(f -> f.name().startsWith("auto_")).min(Fi::lastModified);

            if(newestSave == null){
                err("未找到自动保存！键入 `config autosave true` 以启用自动保存。");
                return;
            }

            if(!SaveIO.isSaveValid(newestSave)){
                err("未找到（有效的）") save data found for slot.");
                return;
            }

            Core.app.post(() -> {
                try{
                    SaveIO.load(newestSave);
                    state.rules.sector = null;
                    info("已加载保存。");
                    state.set(State.playing);
                    netServer.openServer();
                }catch(Throwable t){
                    err("加载保存失败。文件过时或损坏。");
                }
            });
        });

        handler.register("load", "<存档槽>", "从指定存档槽加载存档。", arg -> {
            if(state.isGame()){
                err("已主持。键入 'stop' 以停止主持。");
                return;
            }

            Fi file = saveDirectory.child(arg[0] + "." + saveExtension);

            if(!SaveIO.isSaveValid(file)){
                err("未找到（有效的）") save data found for slot.");
                return;
            }

            Core.app.post(() -> {
                try{
                    SaveIO.load(file);
                    state.rules.sector = null;
                    info("已加载保存。");
                    state.set(State.playing);
                    netServer.openServer();
                }catch(Throwable t){
                    err("加载保存失败。文件过时或损坏。");
                }
            });
        });

        handler.register("save", "<存档槽>", "保存状态至存档槽", arg -> {
            if(!state.isGame()){
                err("未主持。首先主持游戏。");
                return;
            }

            Fi file = saveDirectory.child(arg[0] + "." + saveExtension);

            Core.app.post(() -> {
                SaveIO.save(file);
                info("已保存到 @。", file);
            });
        });

        handler.register("saves", "列出保存目录中的所有存档。", arg -> {
            info("保存文件： ");
            for(Fi file : saveDirectory.list()){
                if(file.extension().equals(saveExtension)){
                    info("| @", file.nameWithoutExtension())));
                }
            }
        });

        handler.register("gameover", "强制结束游戏。", arg -> {
            if(state.isMenu()){
                err("未玩任何地图。");
                return;
            }

            info("核心已摧毁。");
            inGameOverWait = false;
            Events.fire(new GameOverEvent(state.rules.waveTeam));
        });

        handler.register("info", "<IP/UUID/name...>", "查找玩家信息。可选择性检查玩家曾用的所有名称或IP。", arg -> {
            ObjectSet<PlayerInfo> infos = netServer.admins.findByName(arg[0]);

            if(infos.size > 0){
                info("找到的玩家：@", infos.size);

                int i = 0;
                for(PlayerInfo info : infos){
                    info("[@] 玩家 '@' / UUID @ / RAW @ 的追踪信息", i++, info.plainLastName()), info.id, info.lastName);
                    info("  使用过的所有名称：@", info.names);
                    info("  IP：@", info.lastIP);
                    info("  使用过的所有IP：@", info.ips);
                    info("  加入次数：@", info.timesJoined);
                    info("  被踢次数：@", info.timesKicked);
                }
            }else{
                info("未找到该名字的任何人。");
            }
        });

        handler.register("search", "<name...>", "搜索使用过部分名称的玩家。", arg -> {
            ObjectSet<PlayerInfo> infos = netServer.admins.searchNames(arg[0]);

            if(infos.size > 0){
                info("找到的玩家：@", infos.size);

                int i = 0;
                for(PlayerInfo info : infos){
                    info("- [@] '@' / @", i++, info.plainLastName())), info.id);
                }
            }else{
                info("未找到该名字的任何人。");
            }
        });

        handler.register("gc", "回收内存 测试", arg -> {
            int pre = (int)(Core.app.getJavaHeap() / 1024 / 1024);
            System.gc();
            int post = (int)(Core.app.getJavaHeap() / 1024 / 1024);
            info("@ MB 收集完成。内存使用量现为 @ MB。", pre - post, post);
        });

        handler.register("yes", "Run the last suggested incorrect command.", arg -> {
            if(suggested == null){
                err("没有任何可以确认的内容。");
            }else{
                handleCommandString(suggested);
            }
        });

        mods.eachClass(p -> p.registerServerCommands(handler));
    }

    public void handleCommandString(String line){
        CommandResponse response = handler.handleMessage(line);

        if(response.type == ResponseType.unknownCommand){

            int minDst = 0;
            Command closest = null;

            for(Command command : handler.getCommandList()){
                int dst = Strings.levenshtein(command.text, response.runCommand);
                if(dst < 3 && (closest == null || dst < minDst)){
                    minDst = dst;
                    closest = command;
                }
            }

            if(closest != null && !closest.text.equals("yes")){
                err("命令未找到。您是否是指 \"" + closest.text + "\"？");
                suggested = line.replace(response.runCommand, closest.text);
            }else{
                err("无效命令。键入 'help' 获取帮助。");
            }
        }else if(response.type == ResponseType.fewArguments){
            err("命令参数过少。用法： " + response.command.text + " " + response.command.paramText);
        }else if(response.type == ResponseType.manyArguments){
            err("命令参数过多。用法： " + response.command.text + " " + response.command.paramText);
        }else if(response.type == ResponseType.valid){
            suggested = null;
        }
    }

    /**
     * @deprecated
     * Use {@link Maps#setNextMapOverride(Map)} instead.
     */
    @Deprecated
    public void setNextMap(Map map){
        maps.setNextMapOverride(map);
    }

    /**
     * Cancels the world load timer task, if it is scheduled. Can be useful for stopping a server or hosting a new game.
     */
    public void cancelPlayTask(){
        if(lastTask != null) lastTask.cancel();
    }

    /**
     * Resets the world state, starts a new game.
     * @param run What task to run to load a new world.
     */
    public void play(Runnable run){
        play(true, run);
    }

    /**
     * Resets the world state, starts a new game.
     * @param wait Whether to wait for {@link Config#roundExtraTime} seconds before starting a new game.
     * @param run What task to run to load a new world.
     */
    public void play(boolean wait, Runnable run){
        inGameOverWait = true;
        cancelPlayTask();
        
        Runnable reload = () -> {
            try{
                WorldReloader reloader = new WorldReloader();
                reloader.begin();

                run.run();

                state.rules = state.map.applyRules(lastMode);
                logic.play();

                reloader.end();
                inGameOverWait = false;
            }catch(MapException e){
                err("@: @", e.map.plainName())), e.getMessage());
                net.closeServer();
            }
        };

        if(wait){
            lastTask = Timer.schedule(reload, Config.roundExtraTime.num());
        }else{
            reload.run();
        }
    }

    public void logToFile(String text){
        if(currentLogFile != null && currentLogFile.length() > Config.maxLogLength.num()){
            currentLogFile.writeString("[End of log file. Date: " + dateTime.format(LocalDateTime.now()) + "]\n", true);
            currentLogFile = null;
        }

        for(String value : values){
            text = text.replace(value, "");
        }

        if(currentLogFile == null){
            int i = 0;
            while(logFolder.child("log-" + i + ".txt").length() >= Config.maxLogLength.num()){
                i++;
            }

            currentLogFile = logFolder.child("log-" + i + ".txt");
        }

        currentLogFile.writeString(text + "\n", true);
    }

    public void toggleSocket(boolean on){
        if(on && socketThread == null){
            socketThread = new Thread(() -> {
                try{
                    serverSocket = new ServerSocket();
                    serverSocket.bind(new InetSocketAddress(Config.socketInputAddress.string(), Config.socketInputPort.num()));
                    while(true){
                        Socket client = serverSocket.accept();
                        info("&lk 接收到命令套接字连接：&fi@", serverSocket.getLocalSocketAddress()));
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        socketOutput = new PrintWriter(client.getOutputStream(), true);
                        String line;
                        while(client.isConnected() && (line = in.readLine()) != null){
                            String result = line;
                            Core.app.post(() -> handleCommandString(result));
                        }
                        info("&lk 失去命令套接字连接：&fi@", serverSocket.getLocalSocketAddress()));
                        socketOutput = null;
                    }
                }catch(BindException b){
                    err("命令输入套接字已在使用。是否有另一个服务器实例正在运行？");
                }catch(IOException e){
                    if(!e.getMessage().equals("Socket closed") && !e.getMessage().equals("Connection reset")){
                        err("终止套接字服务器。");
                        err(e);
                    }
                }
            });
            socketThread.setDaemon(true);
            socketThread.start();
        }else if(socketThread != null){
            socketThread.interrupt();
            try{
                serverSocket.close();
            }catch(IOException e){
                err(e);
            }
            socketThread = null;
            socketOutput = null;
        }
    }
}
