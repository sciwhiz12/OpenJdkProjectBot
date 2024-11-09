package net.covers1624.projectbot.checker;

import com.google.common.collect.Sets;
import net.covers1624.projectbot.OpenJdkProjectBot;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.net.DownloadAction;
import net.covers1624.quack.net.okhttp.OkHttpDownloadAction;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static net.covers1624.projectbot.OpenJdkProjectBot.JDK_LIST_URL;
import static net.covers1624.projectbot.OpenJdkProjectBot.TIME_FORMAT;

/**
 * Created by covers1624 on 26/5/22.
 */
public class ProjectListChecker {

    private final Path cacheDir;
    private final Path prev;
    private final Path curr;

    public ProjectListChecker(Path cacheDir) {
        this.cacheDir = cacheDir;
        prev = cacheDir.resolve("prev.html");
        curr = cacheDir.resolve("curr.html");
    }

    public Result checkProjectList(Date currTime) throws IOException {
        Map<String, ProjectVersion> newVersions = getVersions(requestDocument());
        Map<String, ProjectVersion> oldVersions = getVersions(getPrevious());

        Set<String> newReleases = Sets.difference(newVersions.keySet(), oldVersions.keySet());
        Set<String> removedReleases = Sets.difference(oldVersions.keySet(), newVersions.keySet());
        Set<String> commonReleases = Sets.intersection(newVersions.keySet(), oldVersions.keySet());

        List<ProjectListChange> changes = new LinkedList<>();
        for (String release : newReleases) {
            ProjectVersion version = newVersions.get(release);
            changes.add(new ProjectListChange(release, null, version.desc));
        }
        for (String release : removedReleases) {
            ProjectVersion version = oldVersions.get(release);
            changes.add(new ProjectListChange(release, version.desc, null));
        }

        for (String release : commonReleases) {
            ProjectVersion newVersion = newVersions.get(release);
            ProjectVersion oldVersion = oldVersions.get(release);
            if (!newVersion.desc.equals(oldVersion.desc)) {
                changes.add(new ProjectListChange(release, oldVersion.desc, newVersion.desc));
            }
        }

        if (!changes.isEmpty()) {
            if (Files.exists(prev)) {
                Path backup = cacheDir.resolve("backups/" + TIME_FORMAT.format(currTime) + ".html");
                Files.move(prev, IOUtils.makeParents(backup));
            }
            Files.copy(curr, IOUtils.makeParents(prev));
        }

        return new Result(changes, newVersions.keySet());
    }

    @Nullable
    private Document getPrevious() throws IOException {
        if (Files.notExists(prev)) return null;

        return Jsoup.parse(Files.readString(prev), JDK_LIST_URL);
    }

    private Document requestDocument() throws IOException {
        DownloadAction action = new OkHttpDownloadAction()
                .setQuiet(false)
                .setUrl(JDK_LIST_URL)
                .setClient(OpenJdkProjectBot.HTTP_CLIENT)
                .setDest(curr);
        action.execute();

        return Jsoup.parse(Files.readString(curr), JDK_LIST_URL);
    }

    public static Map<String, ProjectVersion> getVersions(@Nullable Document document) {
        if (document == null) return Map.of();

        Element ul = document.select("div[id=main] ul").first();
        if (ul == null) return Map.of();

        Map<String, ProjectVersion> versions = new LinkedHashMap<>();
        for (Element li : ul.select("li")) {
            Element a = li.child(0);
            String version = a.text();
            String desc = li.text().replaceFirst(version, "").trim();
            desc = StringUtils.removeStart(desc, "(");
            desc = StringUtils.removeEnd(desc, ")");
            versions.put(version, new ProjectVersion(version, desc));
        }
        return versions;
    }

    public record ProjectVersion(String version, String desc) { }

    public record Result(List<ProjectListChange> changes, Set<String> versions) { }

    public record ProjectListChange(String version, @Nullable String from, @Nullable String to) { }
}
