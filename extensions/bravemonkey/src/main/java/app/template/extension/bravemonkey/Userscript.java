/*
 * Copyright (C) 2026 Alastor Kaneki
 * Ported from Alastor-Kaneki/Morphe-Patches (GPL-3.0-or-later); parts adapted from Violentmonkey (MIT).
 * Modified for Brave Android.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.template.extension.bravemonkey;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Serializable userscript/userstyle model stored in Brave's private app data. */
final class Userscript {
    static final String KIND_JAVASCRIPT = "javascript";
    static final String KIND_CSS = "css";

    String id;
    String name;
    String namespace;
    String version;
    String description;
    String author;
    String icon;
    String runAt;
    String injectInto;
    String updateUrl;
    String downloadUrl;
    String installUrl;
    String kind;
    String source;
    boolean enabled;
    boolean noFrames;
    long installedAt;
    long updatedAt;
    int sortOrder;

    final List<String> matches = new ArrayList<>();
    final List<String> includes = new ArrayList<>();
    final List<String> excludes = new ArrayList<>();
    final List<String> excludeMatches = new ArrayList<>();
    final List<String> grants = new ArrayList<>();
    final List<String> requires = new ArrayList<>();
    final List<String> resources = new ArrayList<>();
    final List<String> requireSources = new ArrayList<>();
    final List<String> tags = new ArrayList<>();

    Userscript() {
        name = "Untitled userscript";
        namespace = "";
        version = "1.0.0";
        description = "";
        author = "";
        icon = "";
        runAt = "document-end";
        injectInto = "page";
        updateUrl = "";
        downloadUrl = "";
        installUrl = "";
        kind = KIND_JAVASCRIPT;
        source = "";
        enabled = true;
        noFrames = false;
        installedAt = System.currentTimeMillis();
        updatedAt = installedAt;
    }

    Userscript copy() {
        Userscript result = new Userscript();
        result.id = id;
        result.name = name;
        result.namespace = namespace;
        result.version = version;
        result.description = description;
        result.author = author;
        result.icon = icon;
        result.runAt = runAt;
        result.injectInto = injectInto;
        result.updateUrl = updateUrl;
        result.downloadUrl = downloadUrl;
        result.installUrl = installUrl;
        result.kind = kind;
        result.source = source;
        result.enabled = enabled;
        result.noFrames = noFrames;
        result.installedAt = installedAt;
        result.updatedAt = updatedAt;
        result.sortOrder = sortOrder;
        result.matches.addAll(matches);
        result.includes.addAll(includes);
        result.excludes.addAll(excludes);
        result.excludeMatches.addAll(excludeMatches);
        result.grants.addAll(grants);
        result.requires.addAll(requires);
        result.resources.addAll(resources);
        result.requireSources.addAll(requireSources);
        result.tags.addAll(tags);
        return result;
    }

    String effectiveUpdateUrl() {
        if (downloadUrl != null && !downloadUrl.trim().isEmpty()) return downloadUrl.trim();
        if (updateUrl != null && !updateUrl.trim().isEmpty()) return updateUrl.trim();
        return installUrl == null ? "" : installUrl.trim();
    }

    List<String> allPositiveRules() {
        List<String> output = new ArrayList<>(matches.size() + includes.size());
        output.addAll(matches);
        output.addAll(includes);
        return Collections.unmodifiableList(output);
    }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", safe(id));
        object.put("name", safe(name));
        object.put("namespace", safe(namespace));
        object.put("version", safe(version));
        object.put("description", safe(description));
        object.put("author", safe(author));
        object.put("icon", safe(icon));
        object.put("runAt", safe(runAt));
        object.put("injectInto", safe(injectInto));
        object.put("updateUrl", safe(updateUrl));
        object.put("downloadUrl", safe(downloadUrl));
        object.put("installUrl", safe(installUrl));
        object.put("kind", safe(kind));
        object.put("source", safe(source));
        object.put("enabled", enabled);
        object.put("noFrames", noFrames);
        object.put("installedAt", installedAt);
        object.put("updatedAt", updatedAt);
        object.put("sortOrder", sortOrder);
        object.put("matches", toJsonArray(matches));
        object.put("includes", toJsonArray(includes));
        object.put("excludes", toJsonArray(excludes));
        object.put("excludeMatches", toJsonArray(excludeMatches));
        object.put("grants", toJsonArray(grants));
        object.put("requires", toJsonArray(requires));
        object.put("resources", toJsonArray(resources));
        object.put("requireSources", toJsonArray(requireSources));
        object.put("tags", toJsonArray(tags));
        return object;
    }

    static Userscript fromJson(JSONObject object) throws JSONException {
        Userscript result = new Userscript();
        result.id = object.optString("id", "");
        result.name = object.optString("name", "Untitled userscript");
        result.namespace = object.optString("namespace", "");
        result.version = object.optString("version", "1.0.0");
        result.description = object.optString("description", "");
        result.author = object.optString("author", "");
        result.icon = object.optString("icon", "");
        result.runAt = object.optString("runAt", "document-end");
        result.injectInto = object.optString("injectInto", "page");
        result.updateUrl = object.optString("updateUrl", "");
        result.downloadUrl = object.optString("downloadUrl", "");
        result.installUrl = object.optString("installUrl", "");
        result.kind = object.optString("kind", KIND_JAVASCRIPT);
        result.source = object.optString("source", "");
        result.enabled = object.optBoolean("enabled", true);
        result.noFrames = object.optBoolean("noFrames", false);
        result.installedAt = object.optLong("installedAt", System.currentTimeMillis());
        result.updatedAt = object.optLong("updatedAt", result.installedAt);
        result.sortOrder = object.optInt("sortOrder", 0);
        readArray(object.optJSONArray("matches"), result.matches);
        readArray(object.optJSONArray("includes"), result.includes);
        readArray(object.optJSONArray("excludes"), result.excludes);
        readArray(object.optJSONArray("excludeMatches"), result.excludeMatches);
        readArray(object.optJSONArray("grants"), result.grants);
        readArray(object.optJSONArray("requires"), result.requires);
        readArray(object.optJSONArray("resources"), result.resources);
        readArray(object.optJSONArray("requireSources"), result.requireSources);
        readArray(object.optJSONArray("tags"), result.tags);
        return result;
    }

    private static JSONArray toJsonArray(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    private static void readArray(JSONArray array, List<String> output) {
        if (array == null) return;
        for (int index = 0; index < array.length(); index++) {
            String value = array.optString(index, "").trim();
            if (!value.isEmpty()) output.add(value);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
