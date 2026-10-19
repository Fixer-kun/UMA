package tsuki.util

import kotlinx.coroutines.delay
import org.json.JSONObject
import tsuki.MangaLoaderContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Solves a Cloudflare Turnstile challenge headlessly and returns the token.
 *
 * evaluateJs reloads the WebView when baseUrl is non-empty. The first call loads a
 * minimal Turnstile page (via document.write, which executes <script> tags); subsequent calls
 * pass an empty baseUrl so the live page is preserved and the challenge survives across calls.
 *
 * @param context   the parser's MangaLoaderContext
 * @param url       an origin allowed by the Turnstile widget (usually the site base URL)
 * @param siteKey   the Turnstile site key
 * @param timeoutMs total time to wait for the token
 * @throws IllegalStateException on timeout or widget error
 */
suspend fun getTurnstileToken(
    context: MangaLoaderContext,
    url: String,
    siteKey: String,
    timeoutMs: Long = 120_000L,
): String {
    val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html,body{margin:0;padding:0;background:transparent;}</style>
        </head>
        <body>
            <div id="c"></div>
            <script>
                window.__turnstileToken = null;
                window.__turnstileError = null;

                function onload() {
                    turnstile.render('#c', {
                        sitekey: ${JSONObject.quote(siteKey)},
                        callback: function(token) {
                            window.__turnstileToken = token;
                        },
                        'error-callback': function(code) {
                            window.__turnstileError = 'error:' + code;
                        },
                        'expired-callback': function() {
                            window.__turnstileError = 'error:expired';
                        },
                        'timeout-callback': function() {
                            window.__turnstileError = 'error:timeout';
                        }
                    });
                }
            </script>
            <script
                src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=onload"
                onerror="window.__turnstileError = 'error:script_load_failed'">
            </script>
        </body>
        </html>
    """.trimIndent()

    /** Load the Turnstile page. document.write executes <script> tags, innerHTML does not */
    context.evaluateJs(
        url,
        "document.open(); document.write(${JSONObject.quote(html)}); document.close();",
    )

    /** 2. Poll the live page. Empty baseUrl ⇒ no reload, so the widget survives */
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val result = context.evaluateJs(
            "",
            "window.__turnstileToken || window.__turnstileError || ''",
        )

        when {
            result.isNullOrBlank() -> Unit
            result.startsWith("error:") -> {
                val code = result.removePrefix("error:")
                throw IllegalStateException("Turnstile failed: ${turnstileErrorMessage(code)} (code: $code)")
            }
            else -> return result
        }

        delay(500.milliseconds)
    }

    throw IllegalStateException("Turnstile timed out after ${timeoutMs}ms")
}

private fun turnstileErrorMessage(code: String): String = when {
    code == "expired" -> "Expired"
    code == "timeout" -> "Timed out"
    code == "unsupported" -> "WebView doesn't support captcha"
    code == "script_load_failed" -> "Script not loaded"
    code == "110100" || code == "110110" || code == "400020" -> "Sitekey rejected"
    code == "110200" -> "Not authorized for this domain"
    code.startsWith("1106") -> "Timed out"
    code.startsWith("100") -> "Failed to initialize"
    code.startsWith("102") -> "Invalid parameters"
    code == "200100" -> "Wrong Device clock"
    code == "200500" -> "Captcha didn't load"
    code.startsWith("120") -> "Internal Cloudflare error"
    code.startsWith("300") -> "Challenge failed in this browser"
    code.startsWith("600") -> "Challenge not solved"
    else -> "Unknown error"
}
