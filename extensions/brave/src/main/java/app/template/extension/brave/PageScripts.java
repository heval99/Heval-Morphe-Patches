package app.template.extension.brave;

import android.os.Handler;
import android.os.Looper;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in page scripts for "Force copy and paste" and "Force picture-in-picture". Each patch enables
 * its script at app start; after every finished page load the enabled scripts run in the page's main
 * world. The scripts also run in private tabs: they are fixed text that reads nothing and sends nothing.
 */
public final class PageScripts {
    public static final String COPY_PASTE = "copy_paste";
    public static final String PICTURE_IN_PICTURE = "picture_in_picture";

    private static final Set<String> ENABLED = ConcurrentHashMap.newKeySet();
    private static volatile Handler main;

    private PageScripts() { }

    /** Turns one script on. */
    static void enable(String id) {
        if (id != null) ENABLED.add(id);
    }

    /** Called from the patched Application.onCreate by "Force copy and paste". */
    public static void enableCopyPaste() {
        enable(COPY_PASTE);
    }

    /** Called from the patched Application.onCreate by "Force picture-in-picture". */
    public static void enablePictureInPicture() {
        enable(PICTURE_IN_PICTURE);
    }

    /** Called from Brave's page-loaded callback with the Tab; never throws. */
    public static void onPageLoad(Object tab) {
        try {
            if (tab == null || ENABLED.isEmpty()) return;
            Handler handler = main;
            if (handler == null) {
                handler = new Handler(Looper.getMainLooper());
                main = handler;
            }
            // Never load the javascript: URL inside Brave's own load callback: post it.
            final Object target = tab;
            handler.post(() -> {
                try {
                    if (TabBridge.urlOf(target) == null) return; // not an http(s) page
                    String script = combined();
                    if (!script.isEmpty()) TabBridge.run(target, script);
                } catch (Throwable ignored) {
                    // never break the page load
                }
            });
        } catch (Throwable ignored) {
            // never break the page load
        }
    }

    private static String join(String... lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) out.append(line).append('\n');
        return out.toString();
    }

    static String combined() {
        StringBuilder out = new StringBuilder();
        if (ENABLED.contains(COPY_PASTE)) out.append("try{").append(forceCopyPasteJs()).append("}catch(e){}\n");
        if (ENABLED.contains(PICTURE_IN_PICTURE)) out.append("try{").append(forcePictureInPictureJs()).append("}catch(e){}\n");
        return out.toString();
    }

    /**
     * Makes text selectable and takes the teeth out of handlers that block copying: selection CSS for text
     * (not inputs, buttons, selects or editable areas), and a window capture listener for copy, cut, paste,
     * selectstart and contextmenu that clears inline on* blockers on the event's path and turns the event's
     * preventDefault and returnValue into no-ops. Nothing is stopped from propagating, and events inside
     * editable areas are left alone, so editors keep their own paste handling. Handlers the page registered
     * on window before this ran still run first. Everything lives in a closure with a random style id.
     */
    static String forceCopyPasteJs() {
        return join(
            "const id='s'+Math.random().toString(36).slice(2,9);",
            "const st=document.createElement('style');st.id=id;",
            "st.textContent='*:not(input):not(textarea):not(select):not(button):not(button *):not([contenteditable]):not([contenteditable] *)'",
            "+'{-webkit-user-select:text !important;user-select:text !important;-webkit-touch-callout:default !important;}';",
            "(document.head||document.documentElement).appendChild(st);",
            "const events=['copy','cut','paste','selectstart','contextmenu'];",
            "const noop=function(){};",
            "function editable(t){try{return !!(t&&t.closest&&t.closest('[contenteditable=\"\"],[contenteditable=\"true\"],[contenteditable=\"plaintext-only\"]'));}catch(e){return false;}}",
            "function clearInline(el){if(!el)return;",
            "for(let i=0;i<events.length;i++){const p='on'+events[i];",
            "try{if(el[p])el[p]=null;}catch(e){}",
            "try{if(el.removeAttribute)el.removeAttribute(p);}catch(e){}}}",
            "const handler=function(e){try{",
            "const t=e.target;if(editable(t))return;",
            "for(let n=(t&&t.nodeType===1)?t:(t&&t.parentElement);n;n=n.parentElement)clearInline(n);",
            "clearInline(document);clearInline(window);",
            "Object.defineProperty(e,'preventDefault',{value:noop,configurable:true});",
            "Object.defineProperty(e,'returnValue',{get:function(){return true;},set:noop,configurable:true});",
            "}catch(x){}};",
            "for(let i=0;i<events.length;i++)window.addEventListener(events[i],handler,true);");
    }

    /**
     * Removes disablepictureinpicture from every video (also in open shadow roots and in roots
     * attached later) and makes HTMLVideoElement.disablePictureInPicture always false. Closed shadow
     * roots created before this ran cannot be reached.
     */
    static String forcePictureInPictureJs() {
        return join(
            "const key=Symbol.for('_p');if(window[key])return;",
            "try{Object.defineProperty(window,key,{value:1});}catch(e){}",
            "try{Object.defineProperty(HTMLVideoElement.prototype,'disablePictureInPicture',",
            "{get:function(){return false;},set:function(){},configurable:true});}catch(e){}",
            "const observed=(typeof WeakSet!=='undefined')?new WeakSet():null;",
            "let mo=null;",
            "function observeRoot(root){if(!root||!mo)return;",
            "if(observed){if(observed.has(root))return;observed.add(root);}",
            "try{mo.observe(root,{childList:true,subtree:true,attributes:true,attributeFilter:['disablepictureinpicture']});}catch(e){}}",
            "function unlock(root){if(!root||!root.querySelectorAll)return;",
            "if(root.nodeType===11)observeRoot(root);",
            "const videos=root.querySelectorAll('video');",
            "for(let i=0;i<videos.length;i++){try{videos[i].removeAttribute('disablepictureinpicture');}catch(e){}}",
            "const all=root.querySelectorAll('*');",
            "for(let i=0;i<all.length;i++){if(all[i].shadowRoot)unlock(all[i].shadowRoot);}}",
            "try{mo=new MutationObserver(function(mutations){",
            "for(const m of mutations){",
            "if(m.type==='attributes'&&m.target&&m.target.tagName==='VIDEO'){",
            "try{m.target.removeAttribute('disablepictureinpicture');}catch(e){}}",
            "else if(m.addedNodes){for(const n of m.addedNodes){if(n.nodeType===1){",
            "if(n.tagName==='VIDEO'){try{n.removeAttribute('disablepictureinpicture');}catch(e){}}",
            "if(n.shadowRoot)unlock(n.shadowRoot);unlock(n);}}}}});",
            "observeRoot(document.documentElement||document);}catch(e){}",
            "try{const orig=Element.prototype.attachShadow;",
            "if(orig){Element.prototype.attachShadow=function(init){",
            "const sr=orig.apply(this,arguments);if(sr)unlock(sr);return sr;};}}catch(e){}",
            "unlock(document);");
    }
}
