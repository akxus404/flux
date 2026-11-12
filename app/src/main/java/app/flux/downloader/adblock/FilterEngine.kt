package app.flux.downloader.adblock

object ResourceType {
    const val DOC = 1
    const val SUBDOC = 2
    const val SCRIPT = 4
    const val IMAGE = 8
    const val CSS = 16
    const val XHR = 32
    const val MEDIA = 64
    const val FONT = 128
    const val PING = 256
    const val WS = 512
    const val OTHER = 1024
    const val OBJECT = 2048
    const val DEFAULT = SUBDOC or SCRIPT or IMAGE or CSS or XHR or MEDIA or FONT or PING or WS or OTHER or OBJECT

    /** WebView no informa el tipo de recurso: si no se puede deducir, se asume "script/xhr/otro". */
    const val UNKNOWN = SCRIPT or XHR or OTHER or PING
}

class RequestCtx(
    val url: String,
    val host: String,
    val hostStart: Int,
    val hostEnd: Int,
    val pageHost: String?,
    val thirdParty: Boolean,
    val type: Int,
)

class NetRule(
    val segments: Array<String>,
    val hostAnchor: Boolean,
    val startAnchor: Boolean,
    val endAnchor: Boolean,
    val thirdParty: Int,
    val typeMask: Int,
    val include: Array<String>?,
    val exclude: Array<String>?,
    val important: Boolean,
) {
    fun optionsMatch(ctx: RequestCtx): Boolean {
        if ((typeMask and ctx.type) == 0) return false
        if (thirdParty == 1 && !ctx.thirdParty) return false
        if (thirdParty == -1 && ctx.thirdParty) return false
        val ph = ctx.pageHost
        if (include != null) {
            if (ph == null || include.none { domainMatches(ph, it) }) return false
        }
        if (exclude != null && ph != null && exclude.any { domainMatches(ph, it) }) return false
        return true
    }
}

internal fun isAlnum(c: Char): Boolean = (c in 'a'..'z') || (c in '0'..'9')

internal fun domainMatches(host: String, domain: String): Boolean =
    host == domain || host.endsWith(".$domain")

private val secondLevel = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go", "gob", "mil", "nom", "ltd", "plc")

/** Aproximación del dominio registrable (sin lista de sufijos públicos). */
internal fun registrableDomain(host: String): String {
    if (host.isEmpty() || host.all { it.isDigit() || it == '.' }) return host
    val parts = host.split('.')
    if (parts.size <= 2) return host
    val tld = parts.last()
    val sld = parts[parts.size - 2]
    val n = if (tld.length == 2 && sld in secondLevel) 3 else 2
    return parts.takeLast(n).joinToString(".")
}

// ---------------------------------------------------------------------------
// Comparación de patrones ABP: * comodín, ^ separador, | y || anclas
// ---------------------------------------------------------------------------
internal object Matcher {

    fun matches(r: NetRule, ctx: RequestCtx): Boolean {
        val url = ctx.url
        if (r.hostAnchor) {
            var s = ctx.hostStart
            while (s < ctx.hostEnd) {
                if (matchFrom(r, url, s, true)) return true
                val dot = url.indexOf('.', s)
                if (dot < 0 || dot >= ctx.hostEnd) break
                s = dot + 1
            }
            return false
        }
        if (r.startAnchor) return matchFrom(r, url, 0, true)
        return matchFrom(r, url, 0, false)
    }

    private fun matchFrom(r: NetRule, url: String, start: Int, anchored: Boolean): Boolean {
        val segs = r.segments
        val last = segs.indexOfLast { it.isNotEmpty() }
        if (last < 0) return true
        var pos = start
        for (i in 0..last) {
            val seg = segs[i]
            if (seg.isEmpty()) continue
            val mustEnd = r.endAnchor && i == last && segs.size - 1 == last
            if (i == 0 && anchored) {
                val e = matchAt(url, seg, pos)
                if (e < 0 || (mustEnd && e != url.length)) return false
                pos = e
            } else if (mustEnd && seg.indexOf('^') < 0) {
                val st = url.length - seg.length
                if (st < pos || !url.startsWith(seg, st)) return false
                pos = url.length
            } else {
                val e = find(url, seg, pos)
                if (e < 0 || (mustEnd && e != url.length)) return false
                pos = e
            }
        }
        return true
    }

    private fun matchAt(url: String, seg: String, from: Int): Int {
        var p = from
        for (c in seg) {
            if (c == '^') {
                if (p == url.length) continue
                if (!isSeparator(url[p])) return -1
                p++
            } else {
                if (p >= url.length || url[p] != c) return -1
                p++
            }
        }
        return p
    }

    private fun find(url: String, seg: String, from: Int): Int {
        if (seg.indexOf('^') < 0) {
            val i = url.indexOf(seg, from)
            return if (i < 0) -1 else i + seg.length
        }
        var i = from
        while (i <= url.length) {
            val e = matchAt(url, seg, i)
            if (e >= 0) return e
            i++
        }
        return -1
    }

    private fun isSeparator(c: Char): Boolean =
        !((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_' || c == '-' || c == '.' || c == '%')
}

// ---------------------------------------------------------------------------
// Índice de reglas: por dominio, por "token" y reglas sueltas (pocas)
// ---------------------------------------------------------------------------
internal class RuleIndex {
    private val byHost = HashMap<String, ArrayList<NetRule>>()
    private val byToken = HashMap<String, ArrayList<NetRule>>()
    private val loose = ArrayList<NetRule>()

    var size = 0
        private set

    fun add(r: NetRule) {
        size++
        val hk = hostKey(r)
        if (hk != null) {
            byHost.getOrPut(hk) { ArrayList(2) }.add(r)
            return
        }
        val tk = tokenKey(r)
        if (tk != null) byToken.getOrPut(tk) { ArrayList(2) }.add(r) else loose.add(r)
    }

    fun find(ctx: RequestCtx): NetRule? {
        var h = ctx.host
        while (true) {
            val list = byHost[h]
            if (list != null) for (r in list) if (r.optionsMatch(ctx) && Matcher.matches(r, ctx)) return r
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        val url = ctx.url
        val n = url.length
        var i = 0
        while (i < n) {
            if (!isAlnum(url[i])) { i++; continue }
            var j = i
            while (j < n && isAlnum(url[j])) j++
            if (j - i >= 3) {
                val list = byToken[url.substring(i, j)]
                if (list != null) for (r in list) if (r.optionsMatch(ctx) && Matcher.matches(r, ctx)) return r
            }
            i = j
        }
        for (r in loose) if (r.optionsMatch(ctx) && Matcher.matches(r, ctx)) return r
        return null
    }

    private fun hostKey(r: NetRule): String? {
        if (!r.hostAnchor) return null
        val first = r.segments[0]
        var i = 0
        while (i < first.length) {
            val c = first[i]
            if (isAlnum(c) || c == '.' || c == '-') i++ else break
        }
        if (i == 0 || i >= first.length) return null
        val term = first[i]
        if (term != '^' && term != '/' && term != ':' && term != '?') return null
        val key = first.substring(0, i)
        return if (key.contains('.')) key else null
    }

    private fun tokenKey(r: NetRule): String? {
        var best: String? = null
        for ((si, seg) in r.segments.withIndex()) {
            var i = 0
            while (i < seg.length) {
                if (!isAlnum(seg[i])) { i++; continue }
                var j = i
                while (j < seg.length && isAlnum(seg[j])) j++
                val leftOk = if (i > 0) true else (si == 0 && (r.hostAnchor || r.startAnchor))
                val rightOk = if (j < seg.length) true else (si == r.segments.size - 1 && r.endAnchor)
                if (leftOk && rightOk && j - i >= 3 && (best == null || j - i > best.length)) {
                    best = seg.substring(i, j)
                }
                i = j
            }
        }
        return best
    }
}

// ---------------------------------------------------------------------------
// Motor
// ---------------------------------------------------------------------------
class FilterEngine private constructor(
    private val blocks: RuleIndex,
    private val allows: RuleIndex,
    private val hideByHost: Map<String, List<String>>,
    private val unhideByHost: Map<String, Set<String>>,
    private val docAllow: Set<String>,
    private val noCosmetic: Set<String>,
    val genericCss: String,
    val ruleCount: Int,
) {

    fun shouldBlock(url: String, pageHost: String?, type: Int): Boolean {
        if (blocks.size == 0) return false
        if (pageHost != null && isPageAllowed(pageHost)) return false
        val u = url.lowercase()
        val schemeEnd = u.indexOf("://")
        val hs = if (schemeEnd >= 0) schemeEnd + 3 else 0
        var he = hs
        while (he < u.length) {
            val c = u[he]
            if (c == '/' || c == '?' || c == '#' || c == ':') break
            he++
        }
        if (he <= hs) return false
        val host = u.substring(hs, he)
        val thirdParty = pageHost != null && registrableDomain(host) != registrableDomain(pageHost)
        val ctx = RequestCtx(u, host, hs, he, pageHost, thirdParty, type)
        val b = blocks.find(ctx) ?: return false
        return b.important || allows.find(ctx) == null
    }

    private fun isPageAllowed(host: String): Boolean {
        var h = host
        while (true) {
            if (h in docAllow) return true
            val d = h.indexOf('.')
            if (d < 0) return false
            h = h.substring(d + 1)
        }
    }

    /** CSS cosmético específico del sitio (los selectores genéricos van en [genericCss]). */
    fun cssForHost(host: String): String {
        var h = host
        while (true) {
            if (h in noCosmetic) return ""
            val d = h.indexOf('.')
            if (d < 0) break
            h = h.substring(d + 1)
        }
        val selectors = LinkedHashSet<String>()
        val hidden = HashSet<String>()
        h = host
        while (true) {
            hideByHost[h]?.let { selectors.addAll(it) }
            unhideByHost[h]?.let { hidden.addAll(it) }
            val d = h.indexOf('.')
            if (d < 0) break
            h = h.substring(d + 1)
        }
        selectors.removeAll(hidden)
        if (selectors.isEmpty()) return ""
        return selectors.chunked(10).joinToString("") { it.joinToString(",") + "{display:none!important}" }
    }

    companion object {
        fun empty(): FilterEngine =
            FilterEngine(RuleIndex(), RuleIndex(), emptyMap(), emptyMap(), emptySet(), emptySet(), "", 0)
    }

    // -----------------------------------------------------------------------
    // Constructor de reglas a partir de líneas de listas
    // -----------------------------------------------------------------------
    class Builder {
        private val blocks = RuleIndex()
        private val allows = RuleIndex()
        private val generic = LinkedHashSet<String>()
        private val byHost = HashMap<String, ArrayList<String>>()
        private val unhide = HashMap<String, HashSet<String>>()
        private val docAllow = HashSet<String>()
        private val noCosmetic = HashSet<String>()

        var ruleCount = 0
            private set

        private val badSelectorParts = listOf(
            ":has-text(", ":-abp-", ":xpath(", ":matches-", ":upward(", ":nth-ancestor(", ":remove(",
            ":style(", "+js(", ":min-text-length(", ":watch-attr(", ":others(", ":contains(", ":-ext-",
            ":if(", ":if-not(", ":with-text(",
        )

        fun addLine(raw: String) {
            val line = raw.trim()
            if (line.isEmpty()) return
            val c0 = line[0]
            if (c0 == '!' || c0 == '[') return
            val marker = cosmeticMarker(line)
            if (marker >= 0) {
                addCosmetic(line, marker)
                return
            }
            addNetwork(line)
        }

        // ---------------- cosmético ----------------
        private fun cosmeticMarker(line: String): Int {
            var i = line.indexOf('#')
            while (i >= 0) {
                val isMarker = line.startsWith("##", i) || line.startsWith("#@#", i) || line.startsWith("#?#", i) ||
                    line.startsWith("#\$#", i) || line.startsWith("#%#", i) || line.startsWith("#@?#", i) ||
                    line.startsWith("#@\$#", i)
                if (isMarker) {
                    var ok = true
                    for (k in 0 until i) {
                        val ch = line[k]
                        if (!(ch.isLetterOrDigit() || ch == '.' || ch == ',' || ch == '~' || ch == '-' || ch == '*' || ch == '_')) {
                            ok = false
                            break
                        }
                    }
                    if (ok) return i
                }
                i = line.indexOf('#', i + 1)
            }
            return -1
        }

        private fun addCosmetic(line: String, i: Int) {
            val marker = when {
                line.startsWith("#@#", i) -> "#@#"
                line.startsWith("##", i) -> "##"
                else -> return // procedurales, estilos y scriptlets: no soportados
            }
            val selector = line.substring(i + marker.length).trim()
            if (!selectorOk(selector)) return
            val domains = line.substring(0, i).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val positive = domains.filter { !it.startsWith("~") && !it.contains('*') }

            if (marker == "#@#") {
                for (d in positive) unhide.getOrPut(d) { HashSet() }.add(selector)
                return
            }
            ruleCount++
            if (domains.isEmpty() || positive.isEmpty()) {
                if (generic.size < 12000) generic.add(selector)
            } else {
                for (d in positive) byHost.getOrPut(d) { ArrayList() }.add(selector)
            }
        }

        private fun selectorOk(s: String): Boolean {
            if (s.isEmpty() || s.length > 300) return false
            if (s[0] == '^') return false
            if (s.indexOf('{') >= 0 || s.indexOf('}') >= 0 || s.indexOf('<') >= 0 || s.indexOf('\n') >= 0) return false
            return badSelectorParts.none { s.contains(it) }
        }

        // ---------------- red ----------------
        private fun typeBit(name: String): Int = when (name) {
            "document", "doc" -> ResourceType.DOC
            "subdocument", "frame" -> ResourceType.SUBDOC
            "script" -> ResourceType.SCRIPT
            "image" -> ResourceType.IMAGE
            "stylesheet", "css" -> ResourceType.CSS
            "xmlhttprequest", "xhr" -> ResourceType.XHR
            "media" -> ResourceType.MEDIA
            "font" -> ResourceType.FONT
            "ping" -> ResourceType.PING
            "websocket" -> ResourceType.WS
            "other" -> ResourceType.OTHER
            "object", "object-subrequest" -> ResourceType.OBJECT
            else -> 0
        }

        private fun addNetwork(line0: String) {
            var line = line0
            var exception = false
            if (line.startsWith("@@")) {
                exception = true
                line = line.substring(2)
            }

            var opts: String? = null
            val d = line.lastIndexOf('$')
            if (d >= 0) {
                val o = line.substring(d + 1)
                if (o.isNotEmpty() && o.indexOf('/') < 0 && o.indexOf('*') < 0) {
                    opts = o
                    line = line.substring(0, d)
                }
            }

            var thirdParty = 0
            val inc = ArrayList<String>()
            val exc = ArrayList<String>()
            var domainOpt = false
            var pos = 0
            var neg = 0
            var important = false
            var pageLevel = 0 // 1 = $document, 2 = $elemhide / $generichide

            if (opts != null) {
                for (raw in opts.split(',')) {
                    var o = raw.trim()
                    if (o.isEmpty()) continue
                    val negated = o.startsWith("~")
                    if (negated) o = o.substring(1)
                    val eq = o.indexOf('=')
                    val name = (if (eq >= 0) o.substring(0, eq) else o).lowercase()
                    val value = if (eq >= 0) o.substring(eq + 1) else ""
                    val bit = typeBit(name)
                    when {
                        bit != 0 -> if (negated) neg = neg or bit else pos = pos or bit
                        name == "third-party" || name == "3p" -> thirdParty = if (negated) -1 else 1
                        name == "first-party" || name == "1p" -> thirdParty = if (negated) 1 else -1
                        name == "important" -> important = true
                        name == "match-case" || name == "redirect" -> Unit
                        name == "all" -> pos = pos or ResourceType.DEFAULT
                        name == "elemhide" || name == "generichide" || name == "ehide" || name == "ghide" -> {
                            if (exception) pageLevel = 2 else return
                        }
                        name == "domain" -> {
                            domainOpt = true
                            for (dm in value.split('|')) {
                                val dd = dm.trim().lowercase()
                                if (dd.isEmpty() || dd.contains('*') || dd.contains('/')) continue
                                if (dd.startsWith("~")) exc.add(dd.substring(1)) else inc.add(dd)
                            }
                        }
                        else -> return // popup, csp, removeparam, replace, etc.: no soportado
                    }
                }
            }
            if (domainOpt && inc.isEmpty() && exc.isEmpty()) return
            if (exception && pageLevel == 0 && pos == ResourceType.DOC) pageLevel = 1

            var p = line.trim()
            if (p.length > 2 && p.startsWith("/") && p.endsWith("/")) return // expresiones regulares: no soportadas

            var host = false
            var start = false
            var end = false
            if (p.startsWith("||")) {
                host = true
                p = p.substring(2)
            } else if (p.startsWith("|")) {
                start = true
                p = p.substring(1)
            }
            if (p.endsWith("|")) {
                end = true
                p = p.substring(0, p.length - 1)
            }
            p = p.lowercase()

            if (pageLevel != 0) {
                val h = p.takeWhile { it.isLetterOrDigit() || it == '.' || it == '-' }
                if (h.contains('.')) {
                    if (pageLevel == 1) docAllow.add(h) else noCosmetic.add(h)
                }
                return
            }
            if (!exception && pos == ResourceType.DOC) return // bloqueo de documento: lo cubre Navegación segura

            val domainsGiven = inc.isNotEmpty()
            if ((p.isEmpty() || p == "*") && !domainsGiven) return
            if (p.length < 3 && !domainsGiven && !host) return

            val mask = if (pos != 0) pos else (ResourceType.DEFAULT and neg.inv())
            if ((mask and ResourceType.DEFAULT) == 0) return

            val rule = NetRule(
                segments = p.split('*').toTypedArray(),
                hostAnchor = host,
                startAnchor = start,
                endAnchor = end,
                thirdParty = thirdParty,
                typeMask = mask,
                include = if (inc.isEmpty()) null else inc.toTypedArray(),
                exclude = if (exc.isEmpty()) null else exc.toTypedArray(),
                important = important,
            )
            if (exception) allows.add(rule) else blocks.add(rule)
            ruleCount++
        }

        fun build(): FilterEngine {
            val css = generic.chunked(25).joinToString("") { it.joinToString(",") + "{display:none!important}" }
            return FilterEngine(blocks, allows, byHost, unhide, docAllow, noCosmetic, css, ruleCount)
        }
    }
}
