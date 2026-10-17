package info.socrtwo.quillpost.web

/**
 * Pre-configured mail providers: the hundred most used mailbox domains worldwide, Proton, and
 * the large regional providers of the United States, Britain, Europe, China, Japan, South
 * Korea, India, Russia, Brazil and Australia. The setup wizard shows these as tiles; the
 * autodiscovery uses the same table before asking the network.
 *
 * Settings are the providers' published IMAP/SMTP values. Several historic domains have been
 * merged into other companies (Verizon → AOL, Erols → Astound/RCN, Alice/Tin → TIM, Voilà →
 * Orange, Chello → Ziggo, Zonnet/Planet/HetNet → KPN, …); those entries point at the
 * successor's servers and say so in their notes.
 */
object Providers {
    enum class Region(val label: String) {
        POPULAR("Most popular"),
        US("United States & Canada"),
        UK("United Kingdom & Ireland"),
        EU("Europe"),
        CN("China"),
        JP("Japan"),
        KR("South Korea"),
        IN("India"),
        RU("Russia"),
        LATAM("Brazil & Latin America"),
        AU("Australia & New Zealand"),
        PRIVACY("Privacy-focused"),
        BUSINESS("Business & hosting")
    }

    data class ProviderDef(
        val id: String,
        val label: String,
        val region: Region,
        val domains: List<String>,
        val imapHost: String,
        val imapPort: Int = 993,
        val imapSecurity: String = "SSL_TLS",
        val smtpHost: String,
        val smtpPort: Int = 587,
        val smtpSecurity: String = "STARTTLS",
        val notes: List<String> = emptyList(),
        val appPasswordUrl: String? = null,
        val oauthOnly: Boolean = false,
        /** Sign in with the part before the @ instead of the full address. */
        val usernameLocalPart: Boolean = false,
        /** Position on the "Most popular" row (1 = first); 0 = only listed under its region. */
        val popular: Int = 0,
        /** Shown when the historic brand has been merged or discontinued. */
        val status: String? = null,
        /** The provider offers no IMAP/SMTP at all (or only via a bridge). */
        val unsupported: Boolean = false
    )

    private const val APP_PW = "requires an app password (not your normal password) for mail apps."

    val all: List<ProviderDef> = listOf(
        // ---------------------------------------------------------------- global giants
        ProviderDef("gmail", "Gmail", Region.POPULAR, listOf("gmail.com", "googlemail.com"),
            "imap.gmail.com", 993, "SSL_TLS", "smtp.gmail.com", 587, "STARTTLS",
            listOf("Google $APP_PW Turn on 2-Step Verification, then create one.", "IMAP must be enabled in Gmail settings → Forwarding and POP/IMAP."),
            "https://myaccount.google.com/apppasswords", popular = 1),
        ProviderDef("yahoo", "Yahoo Mail", Region.POPULAR,
            listOf("yahoo.com", "ymail.com", "rocketmail.com", "yahoo.co.uk", "yahoo.fr", "yahoo.com.br", "yahoo.co.in", "yahoo.in", "yahoo.es", "yahoo.it", "yahoo.de", "yahoo.ca", "yahoo.com.au", "yahoo.com.ar", "yahoo.com.mx", "yahoo.co.id", "yahoo.com.sg", "yahoo.com.hk", "yahoo.com.tw", "yahoo.com.ph", "yahoo.com.my", "yahoo.co.nz", "yahoo.ie", "yahoo.gr", "yahoo.se", "yahoo.dk", "yahoo.no", "yahoo.pl", "yahoo.ro", "yahoo.com.tr", "yahoo.com.vn", "yahoo.co.th", "yahoo.co.za", "yahoo.cn", "rogers.com", "btinternet.com"),
            "imap.mail.yahoo.com", 993, "SSL_TLS", "smtp.mail.yahoo.com", 465, "SSL_TLS",
            listOf("Yahoo $APP_PW Create one under Account security → App passwords."),
            "https://login.yahoo.com/myaccount/security/app-password", popular = 2),
        ProviderDef("outlook", "Outlook.com / Hotmail / Live / MSN", Region.POPULAR,
            listOf("hotmail.com", "outlook.com", "live.com", "msn.com", "hotmail.co.uk", "hotmail.fr", "hotmail.it", "hotmail.es", "hotmail.de", "hotmail.nl", "hotmail.be", "hotmail.ca", "hotmail.com.au", "hotmail.com.br", "hotmail.com.ar", "hotmail.co.jp", "live.fr", "live.co.uk", "live.nl", "live.it", "live.de", "live.be", "live.ca", "live.com.au", "live.com.mx", "live.se", "live.dk", "live.no", "outlook.fr", "outlook.es", "outlook.it", "outlook.de", "outlook.co.uk", "outlook.com.au", "outlook.com.br", "outlook.jp", "outlook.in", "windowslive.com", "passport.com"),
            "outlook.office365.com", 993, "SSL_TLS", "smtp-mail.outlook.com", 587, "STARTTLS",
            listOf("Microsoft has moved personal Outlook.com/Hotmail accounts to OAuth-only sign-in for IMAP; a plain password or app password is usually rejected. Try an app password first; if it fails, the account needs a client with Microsoft OAuth support."),
            "https://account.live.com/proofs/manage/additional", oauthOnly = true, popular = 3),
        ProviderDef("aol", "AOL Mail", Region.POPULAR, listOf("aol.com", "aim.com", "aol.co.uk", "aol.de", "aol.fr"),
            "imap.aol.com", 993, "SSL_TLS", "smtp.aol.com", 465, "SSL_TLS",
            listOf("AOL $APP_PW"), "https://login.aol.com/myaccount/security/app-password", popular = 4),
        ProviderDef("icloud", "iCloud Mail", Region.POPULAR, listOf("icloud.com", "me.com", "mac.com"),
            "imap.mail.me.com", 993, "SSL_TLS", "smtp.mail.me.com", 587, "STARTTLS",
            listOf("iCloud requires an app-specific password generated at appleid.apple.com (two-factor authentication must be on)."),
            "https://appleid.apple.com/account/manage", popular = 5),
        ProviderDef("proton", "Proton Mail", Region.PRIVACY, listOf("proton.me", "protonmail.com", "protonmail.ch", "pm.me"),
            "127.0.0.1", 1143, "STARTTLS", "127.0.0.1", 1025, "STARTTLS",
            listOf("Proton Mail is end-to-end encrypted and has no public IMAP. Install Proton Mail Bridge (paid plans) on the computer that runs QuillPost, then use the Bridge's user name and password with the settings shown here."),
            "https://proton.me/mail/bridge", popular = 6),

        // ---------------------------------------------------------------- United States & Canada
        ProviderDef("comcast", "Comcast / Xfinity", Region.US, listOf("comcast.net", "xfinity.com"),
            "imap.comcast.net", 993, "SSL_TLS", "smtp.comcast.net", 587, "STARTTLS",
            listOf("Third-party access must be enabled at Xfinity Connect → Settings → Security → Third Party Access Security."), popular = 7),
        ProviderDef("verizon", "Verizon (now AOL)", Region.US, listOf("verizon.net"),
            "imap.aol.com", 993, "SSL_TLS", "smtp.aol.com", 465, "SSL_TLS",
            listOf("Verizon mailboxes were moved to AOL in 2017; sign in with your verizon.net address and an AOL app password."),
            "https://login.aol.com/myaccount/security/app-password", status = "Served by AOL since 2017", popular = 8),
        ProviderDef("att", "AT&T / SBCGlobal / BellSouth", Region.US,
            listOf("att.net", "sbcglobal.net", "bellsouth.net", "ameritech.net", "pacbell.net", "swbell.net", "prodigy.net", "nvbell.net", "flash.net", "snet.net", "wans.net"),
            "imap.mail.att.net", 993, "SSL_TLS", "smtp.mail.att.net", 465, "SSL_TLS",
            listOf("AT&T requires a Secure Mail Key instead of your password (myAT&T → Profile → Sign-in info → Secure mail key)."),
            "https://www.att.com/my/#/profile"),
        ProviderDef("cox", "Cox", Region.US, listOf("cox.net"),
            "imap.cox.net", 993, "SSL_TLS", "smtp.cox.net", 465, "SSL_TLS"),
        ProviderDef("spectrum", "Spectrum / Charter", Region.US, listOf("charter.net", "spectrum.net", "twc.com", "rr.com", "roadrunner.com", "brighthouse.com"),
            "mobile.charter.net", 993, "SSL_TLS", "mobile.charter.net", 587, "STARTTLS"),
        ProviderDef("earthlink", "EarthLink", Region.US, listOf("earthlink.net", "mindspring.com", "peoplepc.com"),
            "imap.earthlink.net", 993, "SSL_TLS", "smtpauth.earthlink.net", 587, "STARTTLS"),
        ProviderDef("optimum", "Optimum / Optonline", Region.US, listOf("optonline.net", "optimum.net", "suddenlink.net"),
            "mail.optimum.net", 993, "SSL_TLS", "mail.optimum.net", 465, "SSL_TLS"),
        ProviderDef("frontier", "Frontier", Region.US, listOf("frontiernet.net", "frontier.com", "citlink.net"),
            "imap.frontier.com", 993, "SSL_TLS", "smtp.frontier.com", 465, "SSL_TLS"),
        ProviderDef("windstream", "Windstream", Region.US, listOf("windstream.net"),
            "imap.windstream.net", 993, "SSL_TLS", "smtp.windstream.net", 587, "STARTTLS"),
        ProviderDef("centurylink", "CenturyLink / CenturyTel", Region.US, listOf("centurytel.net", "centurylink.net", "embarqmail.com", "q.com"),
            "mail.centurylink.net", 993, "SSL_TLS", "smtp.centurylink.net", 587, "STARTTLS",
            listOf("CenturyLink now serves the historic centurytel.net and embarqmail.com addresses.")),
        ProviderDef("juno", "Juno / NetZero", Region.US, listOf("juno.com", "netzero.net", "netzero.com"),
            "imap.juno.com", 993, "SSL_TLS", "smtp.juno.com", 465, "SSL_TLS",
            listOf("IMAP/SMTP access requires a paid Juno or NetZero plan.")),
        ProviderDef("erols", "Erols (now Astound / RCN)", Region.US, listOf("erols.com", "rcn.com", "astound.net"),
            "imap.rcn.com", 993, "SSL_TLS", "smtp.rcn.com", 587, "STARTTLS",
            listOf("Erols was bought by RCN (now Astound Broadband); the mailbox is served by RCN's servers. If sign-in fails, ask Astound support for the current host names."),
            status = "Served by Astound / RCN"),
        ProviderDef("shaw", "Shaw", Region.US, listOf("shaw.ca"),
            "imap.shaw.ca", 993, "SSL_TLS", "mail.shaw.ca", 587, "STARTTLS"),
        ProviderDef("sympatico", "Bell / Sympatico", Region.US, listOf("sympatico.ca", "bell.net"),
            "imap.bell.net", 993, "SSL_TLS", "smtphm.sympatico.ca", 587, "STARTTLS"),
        ProviderDef("rogers", "Rogers", Region.US, listOf("rogers.com"),
            "imap.mail.yahoo.com", 993, "SSL_TLS", "smtp.mail.yahoo.com", 465, "SSL_TLS",
            listOf("Rogers mail is hosted by Yahoo; use a Yahoo app password."), "https://login.yahoo.com/myaccount/security/app-password"),
        ProviderDef("telus", "TELUS", Region.US, listOf("telus.net"),
            "imap.telus.net", 993, "SSL_TLS", "smtp.telus.net", 465, "SSL_TLS"),
        ProviderDef("videotron", "Vidéotron", Region.US, listOf("videotron.ca"),
            "imap.videotron.ca", 993, "SSL_TLS", "smtp.videotron.ca", 465, "SSL_TLS"),
        ProviderDef("facebook", "Facebook (@facebook.com)", Region.US, listOf("facebook.com"),
            "", 993, "SSL_TLS", "", 587, "STARTTLS",
            listOf("Facebook closed its @facebook.com email service in 2014; these addresses no longer receive mail."),
            status = "Discontinued in 2014", unsupported = true),

        // ---------------------------------------------------------------- United Kingdom & Ireland
        ProviderDef("bt", "BT Mail", Region.UK, listOf("btinternet.com", "btopenworld.com", "btconnect.com"),
            "mail.btinternet.com", 993, "SSL_TLS", "mail.btinternet.com", 465, "SSL_TLS"),
        ProviderDef("sky", "Sky", Region.UK, listOf("sky.com"),
            "imap.tools.sky.com", 993, "SSL_TLS", "smtp.tools.sky.com", 465, "SSL_TLS"),
        ProviderDef("virginmedia", "Virgin Media / NTL / Blueyonder", Region.UK, listOf("virginmedia.com", "ntlworld.com", "blueyonder.co.uk", "virgin.net"),
            "imap.virginmedia.com", 993, "SSL_TLS", "smtp.virginmedia.com", 465, "SSL_TLS",
            listOf("ntlworld.com and blueyonder.co.uk mailboxes are served by Virgin Media.")),
        ProviderDef("talktalk", "TalkTalk / Tiscali UK", Region.UK, listOf("talktalk.net", "tiscali.co.uk", "lineone.net", "pipex.com"),
            "mail.talktalk.net", 993, "SSL_TLS", "smtp.talktalk.net", 587, "STARTTLS",
            listOf("Tiscali UK addresses have been served by TalkTalk since 2009.")),
        ProviderDef("plusnet", "Plusnet", Region.UK, listOf("plus.com", "plusnet.com"),
            "imap.plus.net", 993, "SSL_TLS", "relay.plus.net", 465, "SSL_TLS"),
        ProviderDef("eir", "eir (Ireland)", Region.UK, listOf("eircom.net", "eir.ie"),
            "mail1.eircom.net", 993, "SSL_TLS", "mail1.eircom.net", 465, "SSL_TLS"),

        // ---------------------------------------------------------------- Europe
        ProviderDef("orange", "Orange / Wanadoo / Voilà", Region.EU, listOf("orange.fr", "wanadoo.fr", "voila.fr"),
            "imap.orange.fr", 993, "SSL_TLS", "smtp.orange.fr", 465, "SSL_TLS",
            listOf("Wanadoo addresses are served by Orange. Voilà mail was closed in 2016; only the Orange servers remain."), popular = 9),
        ProviderDef("free", "Free", Region.EU, listOf("free.fr", "aliceadsl.fr"),
            "imap.free.fr", 993, "SSL_TLS", "smtp.free.fr", 465, "SSL_TLS",
            listOf("Alice ADSL mailboxes were moved to Free.")),
        ProviderDef("sfr", "SFR / Neuf / Club-Internet", Region.EU, listOf("sfr.fr", "neuf.fr", "club-internet.fr", "cegetel.net", "numericable.fr"),
            "imap.sfr.fr", 993, "SSL_TLS", "smtp.sfr.fr", 465, "SSL_TLS",
            listOf("Neuf and Club-Internet addresses are served by SFR.")),
        ProviderDef("laposte", "La Poste", Region.EU, listOf("laposte.net"),
            "imap.laposte.net", 993, "SSL_TLS", "smtp.laposte.net", 465, "SSL_TLS"),
        ProviderDef("bbox", "Bouygues Telecom", Region.EU, listOf("bbox.fr"),
            "imap.bbox.fr", 993, "SSL_TLS", "smtp.bbox.fr", 465, "SSL_TLS"),
        ProviderDef("gmx", "GMX", Region.EU, listOf("gmx.de", "gmx.net", "gmx.at", "gmx.ch", "gmx.com", "gmx.co.uk", "gmx.us", "gmx.fr", "gmx.es", "gmx.it"),
            "imap.gmx.net", 993, "SSL_TLS", "mail.gmx.net", 587, "STARTTLS",
            listOf("IMAP access must be enabled in GMX settings (E-Mail → POP3/IMAP-Abruf).")),
        ProviderDef("webde", "WEB.DE", Region.EU, listOf("web.de"),
            "imap.web.de", 993, "SSL_TLS", "smtp.web.de", 587, "STARTTLS",
            listOf("IMAP access must be enabled in WEB.DE settings (POP3/IMAP-Abruf).")),
        ProviderDef("tonline", "T-Online / Magenta", Region.EU, listOf("t-online.de", "magenta.de"),
            "secureimap.t-online.de", 993, "SSL_TLS", "securesmtp.t-online.de", 465, "SSL_TLS",
            listOf("T-Online requires an E-Mail-Passwort set in the Telekom Kundencenter, separate from the account password.")),
        ProviderDef("freenet", "freenet", Region.EU, listOf("freenet.de"),
            "mx.freenet.de", 993, "SSL_TLS", "mx.freenet.de", 587, "STARTTLS"),
        ProviderDef("arcor", "Arcor (Vodafone)", Region.EU, listOf("arcor.de", "vodafone.de", "vodafonemail.de"),
            "imap.arcor.de", 993, "SSL_TLS", "mail.arcor.de", 465, "SSL_TLS",
            listOf("Sign in with the part before the @ as user name."), usernameLocalPart = true),
        ProviderDef("posteo", "Posteo", Region.PRIVACY, listOf("posteo.de", "posteo.net"),
            "posteo.de", 993, "SSL_TLS", "posteo.de", 465, "SSL_TLS"),
        ProviderDef("mailbox", "mailbox.org", Region.PRIVACY, listOf("mailbox.org"),
            "imap.mailbox.org", 993, "SSL_TLS", "smtp.mailbox.org", 465, "SSL_TLS"),
        ProviderDef("tuta", "Tuta (Tutanota)", Region.PRIVACY, listOf("tutanota.com", "tuta.io", "tuta.com", "tutamail.com", "keemail.me"),
            "", 993, "SSL_TLS", "", 587, "STARTTLS",
            listOf("Tuta does not offer IMAP/SMTP; it cannot be used with a third-party client."), unsupported = true),
        ProviderDef("libero", "Libero", Region.EU, listOf("libero.it", "iol.it", "inwind.it", "blu.it", "giallo.it"),
            "imapmail.libero.it", 993, "SSL_TLS", "smtp.libero.it", 465, "SSL_TLS"),
        ProviderDef("virgilio", "Virgilio", Region.EU, listOf("virgilio.it"),
            "in.virgilio.it", 993, "SSL_TLS", "out.virgilio.it", 465, "SSL_TLS"),
        ProviderDef("tim", "TIM / Alice / Tin.it", Region.EU, listOf("alice.it", "tin.it", "tim.it", "telecomitalia.it"),
            "in.alice.it", 993, "SSL_TLS", "out.alice.it", 465, "SSL_TLS",
            listOf("Alice and Tin.it are TIM Mail; if these servers are refused try imap.tim.it / smtp.tim.it.")),
        ProviderDef("tiscali", "Tiscali Italia", Region.EU, listOf("tiscali.it"),
            "imap.tiscali.it", 993, "SSL_TLS", "smtp.tiscali.it", 465, "SSL_TLS"),
        ProviderDef("ziggo", "Ziggo / Chello / Home.nl", Region.EU, listOf("ziggo.nl", "home.nl", "chello.nl", "upcmail.nl", "casema.nl", "multiweb.nl", "quicknet.nl"),
            "imap.ziggo.nl", 993, "SSL_TLS", "smtp.ziggo.nl", 587, "STARTTLS",
            listOf("home.nl, chello.nl and other legacy UPC/Casema addresses are served by Ziggo.")),
        ProviderDef("kpn", "KPN / Planet / HetNet / Zonnet", Region.EU, listOf("kpnmail.nl", "planet.nl", "hetnet.nl", "zonnet.nl", "xs4all.nl", "telfort.nl", "kpnplanet.nl"),
            "imap.kpnmail.nl", 993, "SSL_TLS", "smtp.kpnmail.nl", 587, "STARTTLS",
            listOf("Planet, HetNet, Zonnet, XS4ALL and Telfort mailboxes are served by KPN; sign in with the full address and the KPN mail password.")),
        ProviderDef("telenet", "Telenet", Region.EU, listOf("telenet.be", "pandora.be", "skynet.be", "proximus.be", "belgacom.net"),
            "imap.telenet.be", 993, "SSL_TLS", "smtp.telenet.be", 587, "STARTTLS",
            listOf("skynet.be, belgacom.net and proximus.be addresses use imap.proximus.be / smtp.proximus.be (same ports) instead.")),
        ProviderDef("proximus", "Proximus / Skynet", Region.EU, listOf("skynet.be", "proximus.be", "belgacom.net"),
            "imap.proximus.be", 993, "SSL_TLS", "smtp.proximus.be", 587, "STARTTLS"),
        ProviderDef("bluewin", "Swisscom / Bluewin", Region.EU, listOf("bluewin.ch", "swisscom.ch"),
            "imaps.bluewin.ch", 993, "SSL_TLS", "smtpauths.bluewin.ch", 465, "SSL_TLS"),
        ProviderDef("seznam", "Seznam", Region.EU, listOf("seznam.cz", "email.cz", "post.cz"),
            "imap.seznam.cz", 993, "SSL_TLS", "smtp.seznam.cz", 465, "SSL_TLS"),
        ProviderDef("wp", "WP / Onet / o2.pl", Region.EU, listOf("wp.pl", "onet.pl", "o2.pl", "onet.eu", "op.pl", "vp.pl"),
            "imap.wp.pl", 993, "SSL_TLS", "smtp.wp.pl", 465, "SSL_TLS",
            listOf("onet.pl / op.pl / vp.pl addresses use imap.poczta.onet.pl / smtp.poczta.onet.pl (same ports).")),
        ProviderDef("interia", "Interia", Region.EU, listOf("interia.pl", "interia.eu"),
            "poczta.interia.pl", 993, "SSL_TLS", "poczta.interia.pl", 465, "SSL_TLS"),
        ProviderDef("telia", "Telia", Region.EU, listOf("telia.com", "telia.se", "telia.fi"),
            "imap.telia.com", 993, "SSL_TLS", "smtp.telia.com", 465, "SSL_TLS"),
        ProviderDef("sapo", "SAPO", Region.EU, listOf("sapo.pt"),
            "imap.sapo.pt", 993, "SSL_TLS", "smtp.sapo.pt", 465, "SSL_TLS"),
        ProviderDef("telefonica", "Movistar / Terra Spain", Region.EU, listOf("telefonica.net", "movistar.es", "terra.es"),
            "imap.movistar.es", 993, "SSL_TLS", "smtp.movistar.es", 465, "SSL_TLS"),
        ProviderDef("ukr", "Ukr.net", Region.EU, listOf("ukr.net"),
            "imap.ukr.net", 993, "SSL_TLS", "smtp.ukr.net", 465, "SSL_TLS",
            listOf("Ukr.net requires an application password for IMAP.")),
        ProviderDef("mailcom", "mail.com", Region.BUSINESS, listOf("mail.com", "email.com", "usa.com", "post.com", "consultant.com", "engineer.com", "myself.com", "europe.com", "asia.com", "techie.com", "writeme.com", "dr.com"),
            "imap.mail.com", 993, "SSL_TLS", "smtp.mail.com", 587, "STARTTLS",
            listOf("IMAP is only available on mail.com Premium accounts.")),
        ProviderDef("zoho", "Zoho Mail", Region.BUSINESS, listOf("zoho.com", "zohomail.com", "zoho.eu", "zohomail.eu", "zoho.in", "zohomail.in"),
            "imap.zoho.com", 993, "SSL_TLS", "smtp.zoho.com", 465, "SSL_TLS",
            listOf("Enable IMAP in Zoho Mail settings; accounts with two-factor authentication need an application-specific password.")),
        ProviderDef("fastmail", "Fastmail", Region.PRIVACY, listOf("fastmail.com", "fastmail.fm", "fastmail.us", "sent.com", "messagingengine.com"),
            "imap.fastmail.com", 993, "SSL_TLS", "smtp.fastmail.com", 465, "SSL_TLS",
            listOf("Fastmail requires an app password for third-party clients."), "https://app.fastmail.com/settings/security/devices"),
        ProviderDef("hushmail", "Hushmail", Region.PRIVACY, listOf("hushmail.com", "hush.com", "hushmail.me"),
            "imap.hushmail.com", 993, "SSL_TLS", "smtp.hushmail.com", 465, "SSL_TLS"),
        ProviderDef("startmail", "StartMail", Region.PRIVACY, listOf("startmail.com"),
            "imap.startmail.com", 993, "SSL_TLS", "smtp.startmail.com", 465, "SSL_TLS"),

        // ---------------------------------------------------------------- China
        ProviderDef("qq", "QQ Mail (腾讯)", Region.CN, listOf("qq.com", "foxmail.com", "vip.qq.com"),
            "imap.qq.com", 993, "SSL_TLS", "smtp.qq.com", 465, "SSL_TLS",
            listOf("QQ Mail requires an authorisation code (授权码) instead of the account password: Settings → Accounts → enable IMAP/SMTP."), popular = 10),
        ProviderDef("netease", "NetEase 163 / 126 (网易)", Region.CN, listOf("163.com", "126.com", "yeah.net", "vip.163.com", "vip.126.com", "188.com"),
            "imap.163.com", 993, "SSL_TLS", "smtp.163.com", 465, "SSL_TLS",
            listOf("NetEase requires a client authorisation code (客户端授权密码). 126.com uses imap.126.com / smtp.126.com, yeah.net uses imap.yeah.net / smtp.yeah.net.")),
        ProviderDef("sina", "Sina Mail (新浪)", Region.CN, listOf("sina.com", "sina.cn", "vip.sina.com"),
            "imap.sina.com", 993, "SSL_TLS", "smtp.sina.com", 465, "SSL_TLS",
            listOf("Enable IMAP in Sina Mail settings and use the client authorisation code.")),
        ProviderDef("sohu", "Sohu Mail (搜狐)", Region.CN, listOf("sohu.com"),
            "imap.sohu.com", 993, "SSL_TLS", "smtp.sohu.com", 465, "SSL_TLS"),
        ProviderDef("aliyun", "Alibaba Mail (阿里云)", Region.CN, listOf("aliyun.com", "aliyun.cn"),
            "imap.aliyun.com", 993, "SSL_TLS", "smtp.aliyun.com", 465, "SSL_TLS"),
        ProviderDef("139", "China Mobile 139", Region.CN, listOf("139.com"),
            "imap.139.com", 993, "SSL_TLS", "smtp.139.com", 465, "SSL_TLS",
            listOf("Enable the client authorisation code in 139 Mail settings.")),
        ProviderDef("189", "China Telecom 189", Region.CN, listOf("189.cn"),
            "imap.189.cn", 993, "SSL_TLS", "smtp.189.cn", 465, "SSL_TLS"),

        // ---------------------------------------------------------------- Japan
        ProviderDef("yahoojp", "Yahoo! JAPAN", Region.JP, listOf("yahoo.co.jp", "ybb.ne.jp"),
            "imap.mail.yahoo.co.jp", 993, "SSL_TLS", "smtp.mail.yahoo.co.jp", 465, "SSL_TLS",
            listOf("Enable IMAP access in Yahoo! JAPAN mail settings (メールソフトでの利用)."), popular = 11),
        ProviderDef("docomo", "NTT docomo", Region.JP, listOf("docomo.ne.jp"),
            "imap.spmode.ne.jp", 993, "SSL_TLS", "smtp.spmode.ne.jp", 465, "SSL_TLS",
            listOf("Sign in with your d ACCOUNT ID and the IMAP password issued under ドコモメール設定 → IMAP専用パスワード."), usernameLocalPart = true),
        ProviderDef("au", "au / ezweb", Region.JP, listOf("au.com", "ezweb.ne.jp"),
            "imap.au.com", 993, "SSL_TLS", "smtp.au.com", 465, "SSL_TLS",
            listOf("Enable au メール app-less access and use the password issued by au.")),
        ProviderDef("nifty", "@nifty", Region.JP, listOf("nifty.com", "nifty.ne.jp"),
            "imap.nifty.com", 993, "SSL_TLS", "smtp.nifty.com", 465, "SSL_TLS"),
        ProviderDef("biglobe", "BIGLOBE", Region.JP, listOf("biglobe.ne.jp"),
            "mail.biglobe.ne.jp", 993, "SSL_TLS", "mail.biglobe.ne.jp", 465, "SSL_TLS"),
        ProviderDef("sonet", "So-net", Region.JP, listOf("so-net.ne.jp"),
            "imap.so-net.ne.jp", 993, "SSL_TLS", "mail.so-net.ne.jp", 587, "STARTTLS"),
        ProviderDef("ocn", "OCN", Region.JP, listOf("ocn.ne.jp"),
            "imap.ocn.ne.jp", 993, "SSL_TLS", "smtp.ocn.ne.jp", 465, "SSL_TLS"),
        ProviderDef("rakuten", "Rakuten / Infoseek", Region.JP, listOf("rakuten.jp", "infoseek.jp", "gol.com"),
            "imap.gol.com", 993, "SSL_TLS", "smtp.gol.com", 465, "SSL_TLS"),

        // ---------------------------------------------------------------- South Korea
        ProviderDef("naver", "Naver (네이버)", Region.KR, listOf("naver.com"),
            "imap.naver.com", 993, "SSL_TLS", "smtp.naver.com", 587, "STARTTLS",
            listOf("Enable IMAP/SMTP in Naver Mail settings (환경설정 → POP3/IMAP 설정)."), popular = 12),
        ProviderDef("daum", "Daum / Kakao Mail (다음)", Region.KR, listOf("daum.net", "hanmail.net", "kakao.com"),
            "imap.daum.net", 993, "SSL_TLS", "smtp.daum.net", 465, "SSL_TLS",
            listOf("Enable IMAP in Daum Mail settings. kakao.com addresses use imap.kakao.com / smtp.kakao.com (same ports).")),
        ProviderDef("nate", "Nate", Region.KR, listOf("nate.com"),
            "imap.nate.com", 993, "SSL_TLS", "smtp.nate.com", 465, "SSL_TLS",
            listOf("IMAP must be enabled in Nate Mail settings.")),

        // ---------------------------------------------------------------- India
        ProviderDef("rediff", "Rediffmail", Region.IN, listOf("rediffmail.com", "rediff.com"),
            "imap.rediffmail.com", 993, "SSL_TLS", "smtp.rediffmail.com", 465, "SSL_TLS",
            listOf("Free Rediffmail accounts offer POP3 only (pop.rediffmail.com, port 995); IMAP needs Rediffmail Pro."), popular = 13),
        ProviderDef("sify", "Sify", Region.IN, listOf("sify.com", "sifymail.com"),
            "imap.sify.com", 993, "SSL_TLS", "smtp.sify.com", 465, "SSL_TLS"),
        ProviderDef("bsnl", "BSNL", Region.IN, listOf("bsnl.in", "bsnl.co.in"),
            "imap.bsnl.in", 993, "SSL_TLS", "smtp.bsnl.in", 465, "SSL_TLS"),
        ProviderDef("indiatimes", "Indiatimes", Region.IN, listOf("indiatimes.com"),
            "imap.indiatimes.com", 993, "SSL_TLS", "smtp.indiatimes.com", 465, "SSL_TLS"),

        // ---------------------------------------------------------------- Russia
        ProviderDef("yandex", "Yandex", Region.RU, listOf("yandex.ru", "yandex.com", "ya.ru", "yandex.ua", "yandex.by", "yandex.kz", "narod.ru"),
            "imap.yandex.com", 993, "SSL_TLS", "smtp.yandex.com", 465, "SSL_TLS",
            listOf("Yandex requires an app password when two-factor authentication is enabled, and IMAP must be switched on in the mail settings."),
            "https://id.yandex.com/security/app-passwords", popular = 14),
        ProviderDef("mailru", "Mail.ru", Region.RU, listOf("mail.ru", "bk.ru", "inbox.ru", "list.ru", "internet.ru"),
            "imap.mail.ru", 993, "SSL_TLS", "smtp.mail.ru", 465, "SSL_TLS",
            listOf("Mail.ru requires an application password (Пароли для внешних приложений)."), "https://account.mail.ru/user/2-step-auth/passwords/"),
        ProviderDef("rambler", "Rambler", Region.RU, listOf("rambler.ru", "lenta.ru", "autorambler.ru", "myrambler.ru", "ro.ru"),
            "imap.rambler.ru", 993, "SSL_TLS", "smtp.rambler.ru", 465, "SSL_TLS",
            listOf("Enable IMAP in Rambler mail settings and use the app password if two-factor authentication is on.")),

        // ---------------------------------------------------------------- Brazil & Latin America
        ProviderDef("uol", "UOL", Region.LATAM, listOf("uol.com.br"),
            "imap.uol.com.br", 993, "SSL_TLS", "smtps.uol.com.br", 465, "SSL_TLS", popular = 15),
        ProviderDef("bol", "BOL", Region.LATAM, listOf("bol.com.br"),
            "imap.bol.com.br", 993, "SSL_TLS", "smtps.bol.com.br", 465, "SSL_TLS"),
        ProviderDef("ig", "iG", Region.LATAM, listOf("ig.com.br"),
            "imap.ig.com.br", 993, "SSL_TLS", "smtp.ig.com.br", 587, "STARTTLS"),
        ProviderDef("terra", "Terra", Region.LATAM, listOf("terra.com.br", "terra.com", "terra.com.ar", "terra.com.mx", "terra.cl", "terra.com.pe", "terra.com.co"),
            "imap.terra.com.br", 993, "SSL_TLS", "smtp.terra.com.br", 587, "STARTTLS"),
        ProviderDef("globo", "Globomail", Region.LATAM, listOf("globo.com", "globomail.com"),
            "imap.globomail.com", 993, "SSL_TLS", "smtp.globomail.com", 465, "SSL_TLS"),
        ProviderDef("prodigy", "Telmex / Prodigy", Region.LATAM, listOf("prodigy.net.mx", "telmex.com"),
            "imap.prodigy.net.mx", 993, "SSL_TLS", "smtp.prodigy.net.mx", 465, "SSL_TLS"),

        // ---------------------------------------------------------------- Australia & New Zealand
        ProviderDef("telstra", "Telstra / BigPond", Region.AU, listOf("bigpond.com", "bigpond.net.au", "telstra.com"),
            "imap.telstra.com", 993, "SSL_TLS", "smtp.telstra.com", 465, "SSL_TLS", popular = 16),
        ProviderDef("optus", "Optus", Region.AU, listOf("optusnet.com.au", "optusnet.au"),
            "mail.optusnet.com.au", 993, "SSL_TLS", "mail.optusnet.com.au", 465, "SSL_TLS"),
        ProviderDef("iinet", "iiNet / Westnet / Internode", Region.AU, listOf("iinet.net.au", "westnet.com.au", "internode.on.net", "adam.com.au"),
            "mail.iinet.net.au", 993, "SSL_TLS", "mail.iinet.net.au", 465, "SSL_TLS"),
        ProviderDef("tpg", "TPG", Region.AU, listOf("tpg.com.au"),
            "mail.tpg.com.au", 993, "SSL_TLS", "mail.tpg.com.au", 465, "SSL_TLS"),
        ProviderDef("xtra", "Spark / Xtra (NZ)", Region.AU, listOf("xtra.co.nz", "spark.co.nz"),
            "imap.xtra.co.nz", 993, "SSL_TLS", "send.xtra.co.nz", 465, "SSL_TLS"),

        // ---------------------------------------------------------------- hosting (matched via MX records)
        ProviderDef("google-workspace", "Google Workspace", Region.BUSINESS, emptyList(),
            "imap.gmail.com", 993, "SSL_TLS", "smtp.gmail.com", 587, "STARTTLS",
            listOf("Google $APP_PW"), "https://myaccount.google.com/apppasswords"),
        ProviderDef("microsoft365", "Microsoft 365", Region.BUSINESS, emptyList(),
            "outlook.office365.com", 993, "SSL_TLS", "smtp.office365.com", 587, "STARTTLS",
            listOf("Microsoft 365 tenants usually have basic authentication disabled; ask your administrator whether IMAP with a password (or app password) is permitted."), oauthOnly = true),
        ProviderDef("gandi", "Gandi", Region.BUSINESS, emptyList(), "mail.gandi.net", 993, "SSL_TLS", "mail.gandi.net", 465, "SSL_TLS"),
        ProviderDef("godaddy", "GoDaddy", Region.BUSINESS, emptyList(), "imap.secureserver.net", 993, "SSL_TLS", "smtpout.secureserver.net", 465, "SSL_TLS"),
        ProviderDef("ionos", "IONOS / 1&1", Region.BUSINESS, listOf("ionos.com", "1and1.com"), "imap.ionos.com", 993, "SSL_TLS", "smtp.ionos.com", 465, "SSL_TLS"),
        ProviderDef("hostinger", "Hostinger", Region.BUSINESS, emptyList(), "imap.hostinger.com", 993, "SSL_TLS", "smtp.hostinger.com", 465, "SSL_TLS"),
        ProviderDef("migadu", "Migadu", Region.BUSINESS, emptyList(), "imap.migadu.com", 993, "SSL_TLS", "smtp.migadu.com", 465, "SSL_TLS")
    )

    /** Hosted-mail providers recognised from a domain's MX records (suffix match). */
    val byMx: List<Pair<List<String>, String>> = listOf(
        listOf("google.com", "googlemail.com") to "google-workspace",
        listOf("outlook.com", "protection.outlook.com", "office365.com") to "microsoft365",
        listOf("yahoodns.net") to "yahoo",
        listOf("icloud.com") to "icloud",
        listOf("zoho.com", "zoho.eu", "zohomail.com") to "zoho",
        listOf("messagingengine.com", "fastmail.com") to "fastmail",
        listOf("protonmail.ch", "proton.me") to "proton",
        listOf("mail.gandi.net") to "gandi",
        listOf("secureserver.net") to "godaddy",
        listOf("ionos.com", "1and1.com", "kundenserver.de") to "ionos",
        listOf("hostinger.com") to "hostinger",
        listOf("migadu.com") to "migadu",
        listOf("yandex.net", "yandex.ru") to "yandex",
        listOf("mail.ru") to "mailru"
    )

    private val byDomain: Map<String, ProviderDef> = buildMap {
        // Later entries never override earlier ones, so e.g. skynet.be resolves to Proximus
        // only where Telenet did not already claim it — Telenet's own note covers that.
        for (p in all) for (d in p.domains) putIfAbsent(d.lowercase(), p)
    }

    fun byId(id: String): ProviderDef? = all.firstOrNull { it.id == id }
    fun forDomain(domain: String): ProviderDef? = byDomain[domain.lowercase()]

    /** Serialisable view for the setup wizard. */
    fun catalogue(): List<ProviderDto> = all.filter { it.domains.isNotEmpty() || it.region == Region.BUSINESS }.map { p ->
        ProviderDto(
            id = p.id, label = p.label, region = p.region.name.lowercase(), regionLabel = p.region.label,
            domains = p.domains, popular = p.popular,
            incomingHost = p.imapHost, incomingPort = p.imapPort, incomingSecurity = p.imapSecurity,
            smtpHost = p.smtpHost, smtpPort = p.smtpPort, smtpSecurity = p.smtpSecurity,
            usernameLocalPart = p.usernameLocalPart, notes = p.notes, appPasswordUrl = p.appPasswordUrl,
            oauthOnly = p.oauthOnly, status = p.status, unsupported = p.unsupported
        )
    }

    fun regions(): List<RegionDto> = Region.entries.map { RegionDto(it.name.lowercase(), it.label) }
}
