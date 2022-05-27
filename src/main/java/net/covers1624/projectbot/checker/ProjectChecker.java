package net.covers1624.projectbot.checker;

import com.google.common.collect.Sets;
import net.covers1624.projectbot.OpenJdkProjectBot;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.net.DownloadAction;
import net.covers1624.quack.net.okhttp.OkHttpDownloadAction;
import org.jetbrains.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static net.covers1624.projectbot.OpenJdkProjectBot.JDK_LIST_URL;
import static net.covers1624.projectbot.OpenJdkProjectBot.TIME_FORMAT;

/**
 * Created by covers1624 on 26/5/22.
 */
public class ProjectChecker {

    private final Path cacheDir;
    private final Path prev;
    private final Path curr;
    private final String version;

    public ProjectChecker(Path cacheDir, String version) {
        this.cacheDir = cacheDir;
        prev = cacheDir.resolve("prev.html");
        curr = cacheDir.resolve("curr.html");
        this.version = version;
    }

    public Result checkProject(Date currTime) throws IOException {
        Document currDoc = requestDocument();
        Document prevDoc = getPrevious();
        Map<String, JEP> newJepMap = getJEPs(currDoc);
        Map<String, JEP> oldJepMap = getJEPs(prevDoc);

        List<JEPChange> jepChanges = new LinkedList<>();
        Set<String> newJeps = Sets.difference(newJepMap.keySet(), oldJepMap.keySet());
        Set<String> remJeps = Sets.difference(oldJepMap.keySet(), newJepMap.keySet());

        for (String id : newJeps) {
            JEP jep = newJepMap.get(id);
            jepChanges.add(new JEPChange(id, jep.desc, true));
        }

        for (String id : remJeps) {
            JEP jep = oldJepMap.get(id);
            jepChanges.add(new JEPChange(id, jep.desc, false));
        }

        if (!jepChanges.isEmpty()) {
            if (Files.exists(prev)) {
                Path backup = cacheDir.resolve("backups/" + TIME_FORMAT.format(currTime) + ".html");
                Files.move(prev, IOUtils.makeParents(backup));
            }
            Files.copy(curr, IOUtils.makeParents(prev));
        }

        return new Result(jepChanges);
    }

    @Nullable
    private Document getPrevious() throws IOException {
        if (Files.notExists(prev)) return null;

        return Jsoup.parse(Files.readString(prev), JDK_LIST_URL + version);
    }

    private Document requestDocument() throws IOException {
        DownloadAction action = new OkHttpDownloadAction()
                .setQuiet(false)
                .setUrl(JDK_LIST_URL + version)
                .setClient(OpenJdkProjectBot.HTTP_CLIENT)
                .setDest(curr);
        action.execute();

        return Jsoup.parse(Files.readString(curr), JDK_LIST_URL + version);
    }

    private Map<String, JEP> getJEPs(@Nullable Document document) {
        if (document == null) return Map.of();

        Map<String, JEP> jeps = new LinkedHashMap<>();
        Elements search = document.select("h2[id=Features] + blockquote > a");
        if (search.isEmpty()) {
            search = document.select(".jeps tbody a");
        }
        for (Element a : search) {
            String href = a.attr("href");
            int lastSlash = href.lastIndexOf('/');

            String id = href.substring(lastSlash + 1);

            jeps.put(id, new JEP(id, a.text()));
        }

        return jeps;
    }

    private record JEP(String id, String desc) { }

    public record JEPChange(String id, String desc, boolean addition) { }

    public record Result(List<JEPChange> jepChanges) { }
}
