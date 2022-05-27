package net.covers1624.projectbot;

import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import net.covers1624.projectbot.checker.ProjectChecker;
import net.covers1624.projectbot.checker.ProjectListChecker;
import net.covers1624.projectbot.discord.DiscordWebhook;
import net.covers1624.projectbot.discord.DiscordWebhook.Embed;
import net.covers1624.quack.gson.JsonUtils;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Created by covers1624 on 26/5/22.
 */
public class OpenJdkProjectBot {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new Gson();

    public static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    public static final MediaType APPLICATION_JSON = MediaType.get("application/json");
    public static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .readTimeout(Duration.ofMinutes(5))
            .connectTimeout(Duration.ofMinutes(5))
            .build();

    public static final DateFormat TIME_FORMAT = new SimpleDateFormat("yyyy-MM-HH-mm-ss");

    public static final String JDK_LIST_URL = "https://openjdk.java.net/projects/jdk/";
    public static final String JEPS_URL = "https://openjdk.java.net/jeps/";
    public static final String DUKE_ICON = "https://ss.ln-k.net/3949e";

    private final Config config;
    private final Path cacheDir;
    private final ProjectListChecker listChecker;
    private final Map<String, ProjectChecker> projectCheckers = new HashMap<>();

    public OpenJdkProjectBot() {
        Path configFile = Path.of("./config.json");
        if (Files.notExists(configFile)) {
            LOGGER.error("Config file does not exist.");
            System.exit(1);
        }
        Config config = null;
        try {
            config = JsonUtils.parse(GSON, configFile, Config.class);
        } catch (IOException | JsonSyntaxException ex) {
            LOGGER.error("Unable to parse config.", ex);
            System.exit(1);
        }
        this.config = config;

        cacheDir = Path.of(config.cacheDir);
        listChecker = new ProjectListChecker(cacheDir.resolve("lists"));
    }

    public static void main(String[] args) throws Throwable {
        new OpenJdkProjectBot().run();
    }

    private void run() throws Throwable {
        EXECUTOR.scheduleAtFixedRate(this::doUpdate, 0, 30, TimeUnit.MINUTES);
    }

    private void doUpdate() {
        LOGGER.info("Running update..");
        try {
            Date currTime = new Date();

            ProjectListChecker.Result listResult = listChecker.checkProjectList(currTime);
            // Remove all old project checkers.
            projectCheckers.keySet().removeAll(Sets.difference(projectCheckers.keySet(), listResult.versions()));

            sendProjectListChanges(listResult);

            for (String version : listResult.versions()) {
                ProjectChecker projectChecker = projectCheckers.computeIfAbsent(version, e -> new ProjectChecker(cacheDir.resolve(e), e));
                ProjectChecker.Result projectResult = projectChecker.checkProject(currTime);
                sendProjectChanges(version, projectResult);
            }

            LOGGER.info("Update check done.");
        } catch (Throwable ex) {
            LOGGER.error("Error checking for updates.", ex);
        }
    }

    private void sendProjectListChanges(ProjectListChecker.Result result) throws IOException {
        if (result.changes().isEmpty()) {
            return;
        }

        LOGGER.info("Detected Project list changes.");
        StringBuilder desc = new StringBuilder();
        for (ProjectListChecker.ProjectListChange change : result.changes()) {
            desc.append("- Release [").append(change.version()).append("](").append(JDK_LIST_URL).append(change.version()).append(") ");
            desc.append(change.from() == null ? "(none)" : "`" + change.from() + "`");
            desc.append(" -> ");
            desc.append(change.to() == null ? "(none)" : "`" + change.to() + "`");
            desc.append("\n");
        }
        for (String webhook : config.webhooks) {
            new DiscordWebhook(webhook)
                    .setUsername("JDK Updates")
                    .setAvatarUrl(DUKE_ICON)
                    .addEmbed(new Embed()
                            .setTitle("JDK Project Listing")
                            .setUrl(JDK_LIST_URL)
                            .setDescription("The following Project versions have changed:\n" + desc.toString().trim())
                    )
                    .execute();
        }
    }

    private void sendProjectChanges(String version, ProjectChecker.Result result) throws IOException {
        if (result.jepChanges().isEmpty()) {
            return;
        }

        LOGGER.info("Detected JEP changes for Project {}", version);

        StringBuilder desc = new StringBuilder();
        for (ProjectChecker.JEPChange change : result.jepChanges()) {
            if (change.addition()) {
                desc.append("Added: ");
            } else {
                desc.append("Removed: ");
            }
            desc.append("[").append(change.id()).append("](").append(JEPS_URL).append(change.id()).append(") - ").append(change.desc());
            desc.append("\n");
        }
        for (String webhook : config.webhooks) {
            new DiscordWebhook(webhook)
                    .setUsername("JEP Updates for Release " + version)
                    .setAvatarUrl(DUKE_ICON)
                    .addEmbed(new Embed()
                            .setTitle("JEP Changes")
                            .setUrl(JDK_LIST_URL)
                            .setDescription("The following Project versions have changed:\n" + desc.toString().trim())
                    )
                    .execute();
        }
    }
}
