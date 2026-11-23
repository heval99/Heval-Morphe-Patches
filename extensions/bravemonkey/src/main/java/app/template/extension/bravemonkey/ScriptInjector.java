/*
 * Copyright (C) 2026 Alastor Kaneki
 * Ported from Alastor-Kaneki/Morphe-Patches (GPL-3.0-or-later); parts adapted from Violentmonkey (MIT).
 * Modified for Brave Android.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.template.extension.bravemonkey;

import java.util.List;

/**
 * Builds self-contained page-context (MAIN world) userscript and userstyle payloads. Pure Java.
 *
 * Known limits of the GM_* shim (kept as upstream):
 * <ul>
 *   <li>{@code @grant} is ignored: every script gets the same shim and runs in the page's own
 *       world with {@code unsafeWindow === window}; {@code @inject-into} is ignored too.</li>
 *   <li>{@code GM_xmlhttpRequest} is the page's {@code fetch}, so CORS and the page CSP apply.</li>
 *   <li>GM values live in the page origin's {@code localStorage} under {@code __monkey:<id>:}, so
 *       the page can read them and they are per origin, not per script install.</li>
 *   <li>{@code @run-at document-start} means "as soon as Brave reports the navigation finished",
 *       not a real pre-load start; document-body/end/idle wait for the DOM events below.</li>
 * </ul>
 *
 * Brave changes: page marker {@code window.__BraveMonkeyLoaded}, no forced re-run (a reload is a new
 * document and re-injects by itself), a newline after every {@code @require}/source so a trailing
 * line comment cannot swallow the wrapper, and every interpolated value goes through {@link #q}.
 */
final class ScriptInjector {
    static final String MARKER = "__BraveMonkeyLoaded";
    static final String INSTALL_SCHEME = "bravemonkey-install";

    private ScriptInjector() { }

    /** Statement list ending in ";" that evaluates to undefined. */
    static String buildPayload(Userscript script) {
        String key = script.id + "@" + script.updatedAt;
        StringBuilder js = new StringBuilder("(function(){");
        js.append("var K=").append(q(key)).append(";")
          .append("var M=window.").append(MARKER).append("=window.").append(MARKER).append("||{};")
          .append("if(M[K])return;M[K]=Date.now();");
        if (script.noFrames) js.append("if(top!==self)return;");
        js.append("var run=function(){").append(api(script));
        if (Userscript.KIND_CSS.equals(script.kind)) {
            js.append("var s=document.getElementById('brave-userstyle-'+K);if(!s){s=document.createElement('style');")
              .append("s.id='brave-userstyle-'+K;(document.head||document.documentElement).appendChild(s)}s.textContent=")
              .append(q(script.source)).append(";");
        } else {
            for (String require : script.requireSources) {
                js.append("try{").append(require)
                  .append("\n}catch(e){console.error('[Brave Userscripts @require]',e)};");
            }
            js.append("try{").append(script.source)
              .append("\n}catch(e){console.error('[Brave Userscripts]',e)};");
        }
        js.append("};").append(schedule(script.runAt)).append("})();");
        return js.toString();
    }

    /**
     * Greasy Fork / Sleazy Fork click bridge: a tap on a .user.js/.user.css install link opens
     * {@code bravemonkey-install://install?url=...} (the exported install review) instead of
     * Brave's download. {@code packageName} is escaped like every other value.
     */
    static String installClickBridge(String packageName) {
        return "(function(){"
                + "if(window.__BraveMonkeyInstallBridge)return;"
                + "window.__BraveMonkeyInstallBridge=true;"
                + "var pkg=" + q(packageName) + ";"
                + "var direct=function(u){return /^https:\\/\\/[^?#]*\\.user\\.(?:js|css)(?:[?#]|$)/i.test(u||'')};"
                + "var resolve=function(a){var u='';try{u=new URL((a&&(a.href||(a.dataset&&a.dataset.codeUrl)))||'',location.href).href}catch(e){}return u};"
                + "document.addEventListener('click',function(e){"
                + "var a=e.target&&e.target.closest?e.target.closest('a[href],[data-code-url]'):null;"
                + "if(!a)return;var u=resolve(a);if(!direct(u))return;"
                + "e.preventDefault();e.stopPropagation();e.stopImmediatePropagation();"
                + "location.href='intent://install?url='+encodeURIComponent(u)+'#Intent;scheme="
                + INSTALL_SCHEME + ";package='+pkg+';end';"
                // Fallback when the intent did not open the review: open the file itself, which
                // BraveMonkey.onPageLoad turns into the install review (direct .user.js URL).
                + "setTimeout(function(){if(document.visibilityState==='visible')location.href=u},1500);"
                + "},true);"
                + "})();";
    }

    private static String api(Userscript s) {
        StringBuilder x = new StringBuilder();
        x.append("var __id=").append(q(s.id)).append(",unsafeWindow=window;")
         .append("var GM_info={script:{name:").append(q(s.name)).append(",namespace:").append(q(s.namespace))
         .append(",version:").append(q(s.version)).append(",description:").append(q(s.description))
         .append(",matches:").append(array(s.matches)).append(",includes:").append(array(s.includes))
         .append(",grant:").append(array(s.grants)).append("},scriptHandler:'Brave Userscripts',version:'1.0'};")
         .append("var P='__monkey:'+__id+':';var get=function(k,d){try{var v=localStorage.getItem(P+k);return v===null?d:JSON.parse(v)}catch(e){return d}};")
         .append("var set=function(k,v){try{localStorage.setItem(P+k,JSON.stringify(v))}catch(e){}return v};")
         .append("function GM_getValue(k,d){return get(k,d)}function GM_setValue(k,v){return set(k,v)}")
         .append("function GM_deleteValue(k){try{localStorage.removeItem(P+k)}catch(e){}}")
         .append("function GM_listValues(){var r=[];try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(k&&k.indexOf(P)===0)r.push(k.slice(P.length))}}catch(e){}return r}")
         .append("function GM_addStyle(c){var s=document.createElement('style');s.textContent=c;(document.head||document.documentElement).appendChild(s);return s}")
         .append("function GM_log(){console.log.apply(console,[").append(q("[Brave Userscripts " + s.name + "]")).append("].concat([].slice.call(arguments)))}")
         .append("function GM_openInTab(u){return open(u,'_blank')}function GM_setClipboard(t){return navigator.clipboard&&navigator.clipboard.writeText(String(t))}")
         .append("function GM_notification(o){o=typeof o==='string'?{text:o}:o||{};if('Notification'in window)Notification.requestPermission().then(function(p){if(p==='granted')new Notification(o.title||")
         .append(q(s.name)).append(",{body:o.text||o.body||''})})}")
         .append("function GM_download(o,n){o=typeof o==='string'?{url:o,name:n}:o||{};var a=document.createElement('a');a.href=o.url;a.download=o.name||'';a.click()}")
         .append("function GM_registerMenuCommand(n,f){window.__BraveMonkeyCommands=window.__BraveMonkeyCommands||{};var l=window.__BraveMonkeyCommands[__id]=window.__BraveMonkeyCommands[__id]||[];l.push({n:String(n),f:f});return l.length-1}")
         .append("function GM_xmlhttpRequest(o){o=o||{};fetch(o.url,{method:o.method||'GET',headers:o.headers,body:o.data,credentials:o.anonymous?'omit':'include'}).then(function(r){return r.text().then(function(t){o.onload&&o.onload({status:r.status,statusText:r.statusText,responseText:t,response:t,finalUrl:r.url,responseHeaders:Array.from(r.headers).map(function(v){return v.join(': ')}).join('\\n')})})}).catch(function(e){o.onerror&&o.onerror(e)});return{abort:function(){}}}")
         .append("var GM={info:GM_info,getValue:function(k,d){return Promise.resolve(GM_getValue(k,d))},setValue:function(k,v){return Promise.resolve(GM_setValue(k,v))},deleteValue:function(k){return Promise.resolve(GM_deleteValue(k))},listValues:function(){return Promise.resolve(GM_listValues())},addStyle:GM_addStyle,log:GM_log,openInTab:GM_openInTab,setClipboard:GM_setClipboard,notification:GM_notification,download:GM_download,registerMenuCommand:GM_registerMenuCommand,xmlHttpRequest:GM_xmlhttpRequest};");
        return x.toString();
    }

    private static String schedule(String runAt) {
        if ("document-start".equals(runAt)) return "run();";
        if ("document-body".equals(runAt)) return "document.body?run():new MutationObserver(function(m,o){if(document.body){o.disconnect();run()}}).observe(document.documentElement,{childList:true,subtree:true});";
        if ("document-idle".equals(runAt)) return "(document.readyState==='complete'?setTimeout(run,1):addEventListener('load',function(){setTimeout(run,1)},{once:true}));";
        return "(document.readyState==='loading'?addEventListener('DOMContentLoaded',run,{once:true}):run());";
    }

    private static String array(List<String> values) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) { if (i > 0) b.append(','); b.append(q(values.get(i))); }
        return b.append(']').toString();
    }

    /** A double-quoted JS string literal; safe for any input. */
    static String q(String value) {
        return '"' + escapeJs(value == null ? "" : value) + '"';
    }

    static String escapeJs(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\'': out.append("\\'"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case ' ': out.append("\\u2028"); break;
                case ' ': out.append("\\u2029"); break;
                case '<': out.append("\\x3c"); break;
                default:
                    if (c < 0x20) out.append(String.format(java.util.Locale.US, "\\x%02x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }
}
