package com.alal.downloader.feature.browser

import android.os.SystemClock
import android.webkit.JavascriptInterface

/** A tap reported by the page script, kept briefly so the navigation it starts can be matched. */
internal class RecordedTap(val tap: BrowserPolicy.Tap, val time: Long = SystemClock.elapsedRealtime()) {
    fun fresh(now: Long = SystemClock.elapsedRealtime()): BrowserPolicy.Tap? = tap.takeIf { now - time in 0..WINDOW_MS }

    private companion object { const val WINDOW_MS = 5_000L }
}

/**
 * Main-frame tap recorder. The injected script sends only the tapped link's or form's resolved address and its
 * opacity/size against the viewport; reports need the page's current key, which every committed navigation replaces.
 */
internal class TapRecorder(private val tab: BrowserTab) {
    @JavascriptInterface
    fun tap(key: String, address: String, form: Boolean, opacity: Double, width: Double, height: Double,
        viewportWidth: Double, viewportHeight: Double) {
        if (key.isEmpty() || key != tab.tapKey) return
        val target = address.takeIf { it.length <= MAX_ADDRESS }.orEmpty()
        tab.tap = RecordedTap(BrowserPolicy.Tap(target, form,
            BrowserPolicy.visible(opacity, width, height, viewportWidth, viewportHeight)))
    }

    companion object {
        const val NAME = "AlalTap"
        private const val MAX_ADDRESS = 8192

        fun script(key: String): String = """
            (function(){var b=window.$NAME;if(!b||window.__alalTap)return;
            try{Object.defineProperty(window,'__alalTap',{value:true});}catch(x){return;}
            window.addEventListener('click',function(e){try{if(!e.isTrusted)return;
            var t=e.target;if(t&&t.nodeType!==1)t=t.parentElement;if(!t||!t.closest)return;
            var el=t.closest('a[href],area[href]'),a='',f=false;
            if(el){a=el.href;if(typeof a!=='string')a=a&&a.baseVal?new URL(a.baseVal,document.baseURI).href:'';}
            else{el=t.closest('button,input[type=submit],input[type=image]');
            if(el&&el.form&&(el.type==='submit'||el.type==='image')){a=el.hasAttribute('formaction')?el.formAction:el.form.action;f=true;}else el=null;}
            var o=1,w=0,h=0;if(el){var r=el.getBoundingClientRect();w=r.width;h=r.height;
            if(getComputedStyle(el).visibility==='hidden')o=0;
            for(var n=el;n&&n.nodeType===1;n=n.parentElement){var v=parseFloat(getComputedStyle(n).opacity);if(!isNaN(v))o*=v;}}
            b.tap('$key',String(a||''),f,o,w,h,window.innerWidth||0,window.innerHeight||0);}catch(x){}},true);})();
        """.trimIndent().replace("\n", "")
    }
}
