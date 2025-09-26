package com.leonardobishop.quests.bukkit.config;

import com.leonardobishop.quests.bukkit.BukkitQuestsPlugin;
import com.leonardobishop.quests.bukkit.hook.itemgetter.ItemGetter;
import com.leonardobishop.quests.bukkit.item.CustomFishingQuestItem;
import com.leonardobishop.quests.bukkit.item.EvenMoreFishQuestItem;
import com.leonardobishop.quests.bukkit.item.ExecutableItemsQuestItem;
import com.leonardobishop.quests.bukkit.item.ItemsAdderQuestItem;
import com.leonardobishop.quests.bukkit.item.MMOItemsQuestItem;
import com.leonardobishop.quests.bukkit.item.NexoQuestItem;
import com.leonardobishop.quests.bukkit.item.OraxenQuestItem;
import com.leonardobishop.quests.bukkit.item.ParsedQuestItem;
import com.leonardobishop.quests.bukkit.item.PyroFishingProQuestItem;
import com.leonardobishop.quests.bukkit.item.QuestItem;
import com.leonardobishop.quests.bukkit.item.QuestItemRegistry;
import com.leonardobishop.quests.bukkit.item.SlimefunQuestItem;
import com.leonardobishop.quests.bukkit.menu.itemstack.QItemStack;
import com.leonardobishop.quests.bukkit.menu.itemstack.QItemStackRegistry;
import com.leonardobishop.quests.bukkit.util.chat.Chat;
import com.leonardobishop.quests.bukkit.util.lang3.StringUtils;
import com.leonardobishop.quests.common.config.ConfigProblem;
import com.leonardobishop.quests.common.config.ConfigProblemDescriptions;
import com.leonardobishop.quests.common.config.QuestsLoader;
import com.leonardobishop.quests.common.logger.QuestsLogger;
import com.leonardobishop.quests.common.quest.Category;
import com.leonardobishop.quests.common.quest.Quest;
import com.leonardobishop.quests.common.quest.QuestManager;
import com.leonardobishop.quests.common.quest.Task;
import com.leonardobishop.quests.common.questcontroller.QuestController;
import com.leonardobishop.quests.common.tasktype.TaskType;
import com.leonardobishop.quests.common.tasktype.TaskTypeManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BukkitQuestsLoader implements QuestsLoader {

    private final BukkitQuestsPlugin plugin;
    private final BukkitQuestsConfig questsConfig;
    private final QuestManager questManager;
    private final TaskTypeManager taskTypeManager;
    private final QuestController questController;
    private final QuestsLogger questsLogger;
    private final QItemStackRegistry qItemStackRegistry;
    private final QuestItemRegistry questItemRegistry;

    private static final Pattern MACRO_PATTERN = Pattern.compile("<\\$m\\s*([^ ]+)\\s*\\$>");

    public BukkitQuestsLoader(BukkitQuestsPlugin plugin) {
        this.plugin = plugin;
        this.questsConfig = (BukkitQuestsConfig) plugin.getQuestsConfig();
        this.questManager = plugin.getQuestManager();
        this.taskTypeManager = plugin.getTaskTypeManager();
        this.questController = plugin.getQuestController();
        this.questsLogger = plugin.getQuestsLogger();
        this.qItemStackRegistry = plugin.getQItemStackRegistry();
        this.questItemRegistry = plugin.getQuestItemRegistry();
    }

    /**
     * Load quests and categories into the respective {@link QuestManager} and register
     * them with tasks in the respective {@link TaskTypeManager}.
     *
     * @param root the directory to load from
     * @return map of configuration issues
     */
    @Override
    public Map<String, List<ConfigProblem>> loadQuests(File root) {
        QuestParsingResult parsingResult = parseQuestFiles(root);
        return applyParsedQuests(parsingResult);
    }

    public QuestParsingResult parseQuestFiles(File root) {
        return parseQuestFiles(root, createMacroSnapshot());
    }

    public QuestParsingResult parseQuestFiles(File root, Map<String, String> macroSnapshot) {
        Map<String, List<ConfigProblem>> configProblems = new HashMap<>();
        Map<String, QuestFileData> questFiles = new LinkedHashMap<>();

        if (root == null || !root.exists()) {
            return new QuestParsingResult(questFiles, configProblems);
        }

        FileVisitor<Path> fileVisitor = new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                try {
                    File questFile = new File(path.toUri());
                    URI relativeLocation = root.toURI().relativize(path.toUri());

                    if (!questFile.getName().toLowerCase().endsWith(".yml")) {
                        return FileVisitResult.CONTINUE;
                    }

                    String data = Files.readString(path, StandardCharsets.UTF_8);
                    StringBuilder processed = new StringBuilder();
                    Matcher matcher = MACRO_PATTERN.matcher(data);

                    int end = 0;
                    while (matcher.find()) {
                        String macro = matcher.group(1);
                        String replacement = macroSnapshot != null ? macroSnapshot.get(macro) : null;
                        if (replacement == null) {
                            replacement = matcher.group(0);
                        }
                        processed.append(data, end, matcher.start()).append(replacement);
                        end = matcher.end();
                    }

                    if (end < data.length()) {
                        processed.append(data, end, data.length());
                    }

                    YamlConfiguration config = new YamlConfiguration();
                    try {
                        config.loadFromString(processed.toString());
                    } catch (InvalidConfigurationException ex) {
                        configProblems.put(relativeLocation.getPath(), Collections.singletonList(new ConfigProblem(
                                ConfigProblem.ConfigProblemType.ERROR,
                                ConfigProblemDescriptions.MALFORMED_YAML.getDescription(),
                                ConfigProblemDescriptions.MALFORMED_YAML.getExtendedDescription(ex.getMessage())
                        )));
                        return FileVisitResult.CONTINUE;
                    }

                    String relativePath = relativeLocation.getPath();
                    if (relativePath == null || relativePath.isEmpty()) {
                        relativePath = questFile.getName();
                    }

                    String id = questFile.getName().replace(".yml", "");

                    questFiles.put(relativePath, new QuestFileData(id, relativePath, config));
                } catch (Exception e) {
                    questsLogger.severe("An exception occurred when attempting to read quest '" + path + "' (will be ignored)");
                    e.printStackTrace();
                }
                return FileVisitResult.CONTINUE;
            }
        };

        try {
            Files.walkFileTree(root.toPath(), fileVisitor);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return new QuestParsingResult(questFiles, configProblems);
    }

    public Map<String, List<ConfigProblem>> applyParsedQuests(QuestParsingResult parsingResult) {
        qItemStackRegistry.clearRegistry();
        questManager.clear();
        taskTypeManager.resetTaskTypes();

        if (parsingResult == null) {
            questsLogger.info("0 quests have been registered.");
            return Collections.emptyMap();
        }

        Map<String, List<ConfigProblem>> configProblems = new HashMap<>();
        parsingResult.getConfigProblems().forEach((path, problems) ->
                configProblems.put(path, new ArrayList<>(problems)));

        Map<String, Quest> pathToQuest = new LinkedHashMap<>();
        Map<String, Map<String, Object>> globalTaskConfig = new HashMap<>();

        if (questsConfig.getConfig().isConfigurationSection("global-task-configuration.types")) {
            for (String type : questsConfig.getConfig().getConfigurationSection("global-task-configuration.types").getKeys(false)) {
                HashMap<String, Object> configValues = new HashMap<>();
                for (String key : questsConfig.getConfig().getConfigurationSection("global-task-configuration.types." + type).getKeys(false)) {
                    configValues.put(key, questsConfig.getConfig().get("global-task-configuration.types." + type + "." + key));
                }
                globalTaskConfig.putIfAbsent(type, configValues);
            }
        }

        ConfigurationSection categories;
        File categoriesFile = new File(plugin.getDataFolder() + File.separator + "categories.yml");
        if (plugin.getConfig().isConfigurationSection("categories")) {
            categories = plugin.getConfig().getConfigurationSection("categories");
        } else {
            if (categoriesFile.exists()) {
                YamlConfiguration categoriesConfiguration = YamlConfiguration.loadConfiguration(categoriesFile);
                if (categoriesConfiguration.isConfigurationSection("categories")) {
                    categories = categoriesConfiguration.getConfigurationSection("categories");
                } else {
                    categories = new YamlConfiguration();
                }
            } else {
                categories = new YamlConfiguration();
            }
        }

        for (String id : categories.getKeys(false)) {
            ItemStack displayItem = plugin.getConfiguredItemStack(id + ".display", categories);
            String guiName = categories.getString(id + ".gui-name");
            boolean permissionRequired = categories.getBoolean(id + ".permission-required", false);
            boolean hidden = categories.getBoolean(id + ".hidden", false);

            Category category = new Category(id, guiName, permissionRequired, hidden);
            questManager.registerCategory(category);
            qItemStackRegistry.register(category, displayItem);
        }

        for (QuestFileData questFileData : parsingResult.getQuestFiles().values()) {
            String relativePath = questFileData.getRelativePath();
            YamlConfiguration config = questFileData.getConfig();
            String id = questFileData.getQuestId();

            List<ConfigProblem> problems = configProblems.computeIfAbsent(relativePath, key -> new ArrayList<>());

            if (!StringUtils.isAlphanumeric(id)) {
                problems.add(new ConfigProblem(ConfigProblem.ConfigProblemType.ERROR,
                        ConfigProblemDescriptions.INVALID_QUEST_ID.getDescription(id),
                        ConfigProblemDescriptions.INVALID_QUEST_ID.getExtendedDescription(id)));
            }

            if (!config.isConfigurationSection("tasks")) {
                problems.add(new ConfigProblem(ConfigProblem.ConfigProblemType.ERROR,
                        ConfigProblemDescriptions.NO_TASKS.getDescription(),
                        ConfigProblemDescriptions.NO_TASKS.getExtendedDescription(),
                        "tasks"));
            } else {
                int validTasks = 0;
                for (String taskId : config.getConfigurationSection("tasks").getKeys(false)) {
                    boolean isValid = true;
                    String taskRoot = "tasks." + taskId;
                    String taskType = config.getString(taskRoot + ".type");

                    if (!config.isConfigurationSection(taskRoot)) {
                        problems.add(new ConfigProblem(ConfigProblem.ConfigProblemType.WARNING,
                                ConfigProblemDescriptions.TASK_MALFORMED_NOT_SECTION.getDescription(taskId),
                                ConfigProblemDescriptions.TASK_MALFORMED_NOT_SECTION.getExtendedDescription(taskId),
                                taskRoot));
                        continue;
                    }

                    if (taskType == null) {
                        problems.add(new ConfigProblem(ConfigProblem.ConfigProblemType.WARNING,
                                ConfigProblemDescriptions.NO_TASK_TYPE.getDescription(),
                                ConfigProblemDescriptions.NO_TASK_TYPE.getExtendedDescription(),
                                taskRoot));
                        continue;
                    }

                    String resolvedTaskTypeName = taskTypeManager.resolveTaskTypeName(taskType);
                    if (resolvedTaskTypeName != null) {
                        TaskType t = taskTypeManager.getTaskType(resolvedTaskTypeName);
                        HashMap<String, Object> configValues = new HashMap<>();
                        for (String key : config.getConfigurationSection(taskRoot).getKeys(false)) {
                            configValues.put(key, config.get(taskRoot + "." + key));
                        }

                        List<ConfigProblem> taskProblems = new ArrayList<>();
                        for (TaskType.ConfigValidator validator : t.getConfigValidators()) {
                            validator.validateConfig(configValues, taskProblems);
                        }

                        for (ConfigProblem problem : taskProblems) {
                            problems.add(new ConfigProblem(problem.getType(), problem.getDescription(),
                                    problem.getExtendedDescription(), taskRoot + "." + problem.getLocation()));
                        }
                    } else {
                        problems.add(new ConfigProblem(ConfigProblem.ConfigProblemType.WARNING,
                                ConfigProblemDescriptions.UNKNOWN_TASK_TYPE.getDescription(taskType),
                                ConfigProblemDescriptions.UNKNOWN_TASK_TYPE.getExtendedDescription(taskType),
                                taskRoot));
                        isValid = false;
                    }

                    if (isValid) {
                        validTasks++;
                    }
                }
                if (validTasks == 0) {
                    problems.add(new ConfigProblem(ConfigProblem.ConfigProblemType.ERROR,
                            ConfigProblemDescriptions.NO_TASKS.getDescription(),
                            ConfigProblemDescriptions.NO_TASKS.getExtendedDescription(),
                            "tasks"));
                }
            }

            boolean error = false;
            for (ConfigProblem problem : problems) {
                if (problem.getType() == ConfigProblem.ConfigProblemType.ERROR) {
                    error = true;
                    break;
                }
            }

            if (!error && !questsConfig.getBoolean("options.error-checking.override-errors", false)) {
                QItemStack displayItem = getQItemStack("display", config);
                List<String> rewards = config.getStringList("rewards");
                List<String> requirements = config.getStringList("options.requires");
                List<String> rewardString = config.getStringList("rewardstring");
                List<String> startString = config.getStringList("startstring");
                List<String> cancelString = config.getStringList("cancelstring");
                List<String> expiryString = config.getStringList("expirystring");
                List<String> startCommands = config.getStringList("startcommands");
                List<String> cancelCommands = config.getStringList("cancelcommands");
                List<String> expiryCommands = config.getStringList("expirycommands");
                String vaultReward = config.getString("vaultreward", null);
                boolean repeatable = config.getBoolean("options.repeatable", false);
                boolean cooldown = config.getBoolean("options.cooldown.enabled", false);
                boolean timeLimit = config.getBoolean("options.time-limit.enabled", false);
                boolean permissionRequired = config.getBoolean("options.permission-required", false);
                boolean autostart = config.getBoolean("options.autostart", false);
                boolean cancellable = config.getBoolean("options.cancellable", true);
                boolean countsTowardsLimit = config.getBoolean("options.counts-towards-limit", true);
                boolean countsTowardsCompleted = config.getBoolean("options.counts-towards-completed", true);
                boolean hidden = config.getBoolean("options.hidden", false);
                int cooldownTime = config.getInt("options.cooldown.time", 10);
                int timeLimtTime = config.getInt("options.time-limit.time", 10);
                int sortOrder = config.getInt("options.sort-order", 1);
                String category = config.getString("options.category");
                Map<String, String> placeholders = new HashMap<>();
                Map<String, String> progressPlaceholders = new HashMap<>();

                if (category != null && category.equals("")) category = null;

                if (questController.getName().equals("daily")) {
                    repeatable = true;
                    cooldown = true;
                    cooldownTime = 0;
                    requirements = Collections.emptyList();
                    permissionRequired = false;
                }

                Quest quest = new Quest.Builder(id)
                        .withRewards(rewards)
                        .withRequirements(requirements)
                        .withRewardString(rewardString)
                        .withStartString(startString)
                        .withCancelString(cancelString)
                        .withExpiryString(expiryString)
                        .withStartCommands(startCommands)
                        .withCancelCommands(cancelCommands)
                        .withExpiryCommands(expiryCommands)
                        .withVaultReward(vaultReward)
                        .withPlaceholders(placeholders)
                        .withProgressPlaceholders(progressPlaceholders)
                        .withCooldown(cooldownTime)
                        .withTimeLimit(timeLimtTime)
                        .withSortOrder(sortOrder)
                        .withCooldownEnabled(cooldown)
                        .withTimeLimitEnabled(timeLimit)
                        .withPermissionRequired(permissionRequired)
                        .withRepeatEnabled(repeatable)
                        .withCancellable(cancellable)
                        .withCountsTowardsLimit(countsTowardsLimit)
                        .withCountsTowardsCompleted(countsTowardsCompleted)
                        .withHidden(hidden)
                        .withAutoStartEnabled(autostart)
                        .inCategory(category)
                        .build();

                if (category != null) {
                    Category c = questManager.getCategoryById(category);
                    if (c != null) {
                        c.registerQuestId(id);
                    } else {
                        String allCategories = questManager.getCategories().stream().map(Category::getId).collect(Collectors.joining(", "));
                        problems.add(new ConfigProblem(ConfigProblem.ConfigProblemType.WARNING,
                                ConfigProblemDescriptions.UNKNOWN_CATEGORY.getDescription(category, allCategories),
                                ConfigProblemDescriptions.UNKNOWN_CATEGORY.getExtendedDescription(category, allCategories),
                                "options.category"));
                    }
                }

                for (String taskId : config.getConfigurationSection("tasks").getKeys(false)) {
                    String taskRoot = "tasks." + taskId;
                    String taskType = config.getString(taskRoot + ".type");
                    String resolvedTaskTypeName = taskTypeManager.resolveTaskTypeName(taskType);
                    if (resolvedTaskTypeName == null) continue;

                    Task task = new Task(taskId, resolvedTaskTypeName);

                    for (String key : config.getConfigurationSection(taskRoot).getKeys(false)) {
                        task.addConfigValue(key, config.get(taskRoot + "." + key));
                    }

                    if (globalTaskConfig.containsKey(taskType)) {
                        for (Map.Entry<String, Object> entry : globalTaskConfig.get(taskType).entrySet()) {
                            if (questsConfig.getBoolean("options.global-task-configuration-override") && task.getConfigValue(entry.getKey()) != null)
                                continue;
                            task.addConfigValue(entry.getKey(), entry.getValue());
                        }
                    }

                    quest.registerTask(task);
                }

                for (String line : displayItem.getLoreNormal()) {
                    findInvalidTaskReferences(quest, line, problems, "display.lore-normal");
                }
                for (String line : displayItem.getLoreStarted()) {
                    findInvalidTaskReferences(quest, line, problems, "display.lore-started");
                }

                if (config.isConfigurationSection("placeholders")) {
                    for (String p : config.getConfigurationSection("placeholders").getKeys(false)) {
                        placeholders.put(p, config.getString("placeholders." + p));
                        findInvalidTaskReferences(quest, config.getString("placeholders." + p), problems, "placeholders." + p);
                    }
                }
                if (config.isConfigurationSection("progress-placeholders")) {
                    for (String p : config.getConfigurationSection("progress-placeholders").getKeys(false)) {
                        progressPlaceholders.put(p, config.getString("progress-placeholders." + p));
                        findInvalidTaskReferences(quest, config.getString("progress-placeholders." + p), problems, "placeholders." + p, true);
                    }
                }
                questManager.registerQuest(quest);
                taskTypeManager.registerQuestTasksWithTaskTypes(quest);
                qItemStackRegistry.register(quest, displayItem);
                if (config.isConfigurationSection("options.locked-display")) {
                    qItemStackRegistry.registerQuestLocked(quest,
                            plugin.getItemGetter().getItem("options.locked-display", config));
                }
                if (config.isConfigurationSection("options.completed-display")) {
                    qItemStackRegistry.registerQuestCompleted(quest,
                            plugin.getItemGetter().getItem("options.completed-display", config));
                }
                if (config.isConfigurationSection("options.cooldown-display")) {
                    qItemStackRegistry.registerQuestCooldown(quest,
                            plugin.getItemGetter().getItem("options.cooldown-display", config));
                }
                if (config.isConfigurationSection("options.permission-display")) {
                    qItemStackRegistry.registerQuestPermission(quest,
                            plugin.getItemGetter().getItem("options.permission-display", config));
                }
                pathToQuest.put(relativePath, quest);
            }

            if (problems.isEmpty()) {
                configProblems.remove(relativePath);
            }
        }

        questsLogger.info(questManager.getQuestMap().size() + " quests have been registered.");

        for (Map.Entry<String, Quest> loadedQuest : pathToQuest.entrySet()) {
            List<ConfigProblem> problems = new ArrayList<>();
            for (String req : loadedQuest.getValue().getRequirements()) {
                if (questManager.getQuestById(req) == null) {
                    problems.add(new ConfigProblem(ConfigProblem.ConfigProblemType.WARNING,
                            ConfigProblemDescriptions.UNKNOWN_REQUIREMENT.getDescription(req),
                            ConfigProblemDescriptions.UNKNOWN_REQUIREMENT.getExtendedDescription(req),
                            "options.requires"));
                }
            }

            if (!problems.isEmpty()) {
                configProblems.computeIfAbsent(loadedQuest.getKey(), key -> new ArrayList<>()).addAll(problems);
            }
        }

        return configProblems;
    }

    public Map<String, String> createMacroSnapshot() {
        Map<String, String> macros = new HashMap<>();
        ConfigurationSection macroSection = questsConfig.getConfig().getConfigurationSection("global-macros");
        if (macroSection != null) {
            for (String key : macroSection.getKeys(false)) {
                String value = macroSection.getString(key);
                if (value != null) {
                    macros.put(key, value);
                }
            }
        }
        return macros;
    }

    public static class QuestParsingResult {

        private final Map<String, QuestFileData> questFiles;
        private final Map<String, List<ConfigProblem>> configProblems;

        public QuestParsingResult(Map<String, QuestFileData> questFiles, Map<String, List<ConfigProblem>> configProblems) {
            this.questFiles = questFiles;
            this.configProblems = configProblems;
        }

        public Map<String, QuestFileData> getQuestFiles() {
            return questFiles;
        }

        public Map<String, List<ConfigProblem>> getConfigProblems() {
            return configProblems;
        }
    }

    public static class QuestFileData {

        private final String questId;
        private final String relativePath;
        private final YamlConfiguration config;

        public QuestFileData(String questId, String relativePath, YamlConfiguration config) {
            this.questId = questId;
            this.relativePath = relativePath;
            this.config = config;
        }

        public String getQuestId() {
            return questId;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public YamlConfiguration getConfig() {
            return config;
        }
    }

    /**
     * Load quest items into the respective quest item registry.
     *
     * @param root the directory to load from
     */
    public void loadQuestItems(File root) {
        questItemRegistry.clearRegistry();

        FileVisitor<Path> fileVisitor = new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                try {
                    File itemFile = new File(path.toUri());
                    if (!itemFile.getName().toLowerCase().endsWith(".yml")) return FileVisitResult.CONTINUE;

                    YamlConfiguration config = new YamlConfiguration();
                    // test file integrity
                    try {
                        config.load(itemFile);
                    } catch (Exception ex) {
                        return FileVisitResult.CONTINUE;
                    }

                    String id = itemFile.getName().replace(".yml", "");

                    if (!StringUtils.isAlphanumeric(id)) {
                        return FileVisitResult.CONTINUE;
                    }

                    QuestItem item;
                    //TODO convert to registry based service
                    switch (config.getString("type", "").toLowerCase()) {
                        default:
                            return FileVisitResult.CONTINUE;
                        case "raw":
                            item = new ParsedQuestItem("raw", id, config.getItemStack("item"));
                            break;
                        case "defined":
                            item = new ParsedQuestItem("defined", id, plugin.getItemGetter().getItem("item", config));
                            break;
                        case "mmoitems":
                            if (!Bukkit.getPluginManager().isPluginEnabled("MMOItems")) return FileVisitResult.CONTINUE;
                            item = new MMOItemsQuestItem(id, config.getString("item.type"), config.getString("item.id"));
                            break;
                        case "slimefun":
                            if (!Bukkit.getPluginManager().isPluginEnabled("Slimefun")) return FileVisitResult.CONTINUE;
                            item = new SlimefunQuestItem(id, config.getString("item.id"));
                            break;
                        case "executableitems":
                            if (!Bukkit.getPluginManager().isPluginEnabled("ExecutableItems")) return FileVisitResult.CONTINUE;
                            item = new ExecutableItemsQuestItem(id, config.getString("item.id"));
                            break;
                        case "itemsadder":
                            if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return FileVisitResult.CONTINUE;
                            item = new ItemsAdderQuestItem(id, config.getString("item.id"));
                            break;
                        case "oraxen":
                            if (!Bukkit.getPluginManager().isPluginEnabled("Oraxen")) return FileVisitResult.CONTINUE;
                            item = new OraxenQuestItem(id, config.getString("item.id"));
                            break;
                        case "pyrofishingpro":
                            if (!Bukkit.getPluginManager().isPluginEnabled("PyroFishingPro")) return FileVisitResult.CONTINUE;
                            item = new PyroFishingProQuestItem(id, config.getInt("item.fish-number", -1), config.getString("item.tier"));
                            break;
                        case "nexo":
                            if (!Bukkit.getPluginManager().isPluginEnabled("Nexo")) return FileVisitResult.CONTINUE;
                            item = new NexoQuestItem(id, config.getString("item.id"));
                            break;
                        case "customfishing":
                            if (!Bukkit.getPluginManager().isPluginEnabled("CustomFishing")) return FileVisitResult.CONTINUE;
                            item = new CustomFishingQuestItem(id, config.contains("item.ids") ? config.getStringList("item.ids") : Collections.singletonList(config.getString("item.id")));
                            break;
                        case "evenmorefish":
                            if (!Bukkit.getPluginManager().isPluginEnabled("EvenMoreFish")) return FileVisitResult.CONTINUE;
                            item = new EvenMoreFishQuestItem(id, config.getString("item.rarity"), config.getString("item.fish"));
                            break;
                    }

                    questItemRegistry.registerItem(id, item);

                } catch (Exception e) {
                    questsLogger.severe("An exception occurred when attempting to load quest item '" + path + "' (will be ignored)");
                    e.printStackTrace();
                }
                return FileVisitResult.CONTINUE;
            }
        };

        try {
            Files.walkFileTree(root.toPath(), fileVisitor);
        } catch (IOException e) {
            e.printStackTrace();
        }

        questsLogger.info(questItemRegistry.getAllItems().size() + " quest items have been registered.");
    }

    private void findInvalidTaskReferences(Quest quest, String s, List<ConfigProblem> configProblems, String location) {
        findInvalidTaskReferences(quest, s, configProblems, location, false);
    }

    private void findInvalidTaskReferences(Quest quest, String s, List<ConfigProblem> configProblems, String location, boolean allowByThis) {
        Matcher matcher = QItemStack.TASK_PLACEHOLDER_PATTERN.matcher(s);

        while (matcher.find()) {
            String taskIdPart = matcher.group(1);
            if (allowByThis && taskIdPart.equals("this")) {
                continue;
            }

            boolean match = false;
            for (Task task : quest.getTasks()) {
                String taskId = task.getId();
                if (taskId.equals(taskIdPart)) {
                    match = true;
                    break;
                }
            }

            if (match) {
                continue;
            }

            configProblems.add(new ConfigProblem(ConfigProblem.ConfigProblemType.WARNING,
                    ConfigProblemDescriptions.UNKNOWN_TASK_REFERENCE.getDescription(taskIdPart),
                    ConfigProblemDescriptions.UNKNOWN_TASK_REFERENCE.getExtendedDescription(taskIdPart),
                    location));
        }
    }

    private QItemStack getQItemStack(String path, FileConfiguration config) {
        String cName = config.getString(path + ".name", path + ".name");
        List<String> cLoreNormal = config.getStringList(path + ".lore-normal");
        List<String> cLoreStarted = config.getStringList(path + ".lore-started");

        List<String> loreNormal = Chat.legacyColor(cLoreNormal);
        List<String> loreStarted = Chat.legacyColor(cLoreStarted);

        String name;
        name = Chat.legacyColor(cName);

        ItemStack is = plugin.getConfiguredItemStack(path, config,
                ItemGetter.Filter.DISPLAY_NAME, ItemGetter.Filter.LORE, ItemGetter.Filter.ENCHANTMENTS);

        return new QItemStack(plugin, name, loreNormal, loreStarted, is);
    }

}
