package app.flux.downloader.browser

/** Lista semilla mínima: protege desde el primer arranque, antes de descargar las listas completas. */
object AdBlocker {

    val seedHosts: Set<String> = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "adservice.google.com", "2mdn.net", "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "pubmatic.com", "rubiconproject.com", "openx.net",
        "casalemedia.com", "amazon-adsystem.com", "advertising.com", "smartadserver.com",
        "serving-sys.com", "bidswitch.net", "mgid.com", "revcontent.com", "zedo.com",
        "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "exosrv.com",
        "juicyads.com", "trafficjunky.com", "trafficfactory.com", "adsterra.com",
        "hilltopads.net", "clickadu.com",
        "google-analytics.com", "scorecardresearch.com", "quantserve.com", "moatads.com",
        "adsafeprotected.com", "hotjar.com", "mixpanel.com",
    )
}
