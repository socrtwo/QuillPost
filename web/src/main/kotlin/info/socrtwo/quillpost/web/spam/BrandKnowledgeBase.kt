package info.socrtwo.quillpost.web.spam

/** A well-known organisation, the names it goes by, and the domains it legitimately mails from. */
data class Brand(val name: String, val aliases: List<String>, val domains: List<String>, val category: String = "")

/**
 * Offline knowledge base used to recognise which organisation a message claims to come from.
 * Nothing here requires a network call. Brands are matched on display name, subject, body
 * signature lines and on look-alike sender domains.
 */
object BrandKnowledgeBase {

    private fun b(name: String, domains: String, aliases: String = "", category: String = "") = Brand(
        name,
        (listOf(name) + aliases.split('|').filter { it.isNotBlank() }).map { it.lowercase() },
        domains.split('|').filter { it.isNotBlank() }.map { it.lowercase() },
        category
    )

    /** Hand-curated core entries; [BrandKnowledgeBaseExtra] adds ~2,000 more from a data table. */
    private val coreBrands: List<Brand> = listOf(
        // Technology / accounts
        b("Microsoft", "microsoft.com|microsoftonline.com|office.com|office365.com|outlook.com|live.com|hotmail.com|msn.com|xbox.com|azure.com|azure.net|skype.com|linkedin.com|github.com|microsoftsupport.com|accountprotection.microsoft.com|email.microsoft.com", "microsoft 365|office 365|office365|outlook|onedrive|azure|microsoft account|windows|xbox|skype|msft", "tech"),
        b("Apple", "apple.com|icloud.com|me.com|mac.com|itunes.com|email.apple.com", "icloud|itunes|app store|apple id|apple pay|applecare|apple support", "tech"),
        b("Google", "google.com|gmail.com|googlemail.com|youtube.com|accounts.google.com|googleplay.com|android.com|google.co.uk|withgoogle.com", "gmail|youtube|google play|google account|google drive|google workspace|google ads|g suite", "tech"),
        b("Amazon", "amazon.com|amazon.co.uk|amazon.ca|amazon.de|amazon.fr|amazon.es|amazon.it|amazon.co.jp|amazon.in|amazon.com.au|amazonaws.com|aws.amazon.com|primevideo.com|audible.com|kindle.com|amazon.jobs", "amazon prime|prime video|aws|amazon web services|audible|kindle|amazon.com", "retail"),
        b("PayPal", "paypal.com|paypal.co.uk|paypal-communication.com|paypal.me|paypalobjects.com", "pay pal|paypal service|paypal support", "finance"),
        b("Netflix", "netflix.com|members.netflix.com|mailer.netflix.com", "", "media"),
        b("Meta / Facebook", "facebook.com|facebookmail.com|meta.com|fb.com|instagram.com|messenger.com|whatsapp.com|metamail.com", "facebook|meta|instagram|messenger|whatsapp", "social"),
        b("X (Twitter)", "x.com|twitter.com|email.x.com|email.twitter.com", "twitter", "social"),
        b("LinkedIn", "linkedin.com|e.linkedin.com|linkedin.email", "", "social"),
        b("TikTok", "tiktok.com|bytedance.com", "", "social"),
        b("Snapchat", "snapchat.com|snap.com", "", "social"),
        b("Pinterest", "pinterest.com|pinterestmail.com", "", "social"),
        b("Reddit", "reddit.com|redditmail.com", "", "social"),
        b("Discord", "discord.com|discordapp.com", "", "social"),
        b("Zoom", "zoom.us|zoom.com", "", "tech"),
        b("Dropbox", "dropbox.com|dropboxmail.com", "", "tech"),
        b("DocuSign", "docusign.com|docusign.net", "docu sign", "tech"),
        b("Adobe", "adobe.com|adobesign.com|acrobat.com|echosign.com", "acrobat|adobe sign|creative cloud", "tech"),
        b("Yahoo", "yahoo.com|yahooinc.com|aol.com|yahoo.co.uk|ymail.com", "yahoo mail|aol", "tech"),
        b("Salesforce", "salesforce.com|exacttarget.com|slack.com|slack-mail.com", "slack", "tech"),
        b("Atlassian", "atlassian.com|atlassian.net|jira.com|trello.com|bitbucket.org", "jira|confluence|trello", "tech"),
        b("Norton", "norton.com|nortonlifelock.com|gendigital.com|lifelock.com", "norton lifelock|norton antivirus|norton security|lifelock", "security"),
        b("McAfee", "mcafee.com", "mcafee antivirus|mcafee security", "security"),
        b("Geek Squad / Best Buy", "bestbuy.com|geeksquad.com|emailinfo.bestbuy.com", "geek squad|geeksquad|best buy|bestbuy", "retail"),
        b("GoDaddy", "godaddy.com|secureserver.net", "go daddy", "tech"),
        b("Namecheap", "namecheap.com", "", "tech"),
        b("Squarespace", "squarespace.com", "", "tech"),
        b("Wix", "wix.com", "", "tech"),
        b("Shopify", "shopify.com|shopifyemail.com", "", "tech"),
        b("Intuit", "intuit.com|quickbooks.com|turbotax.com|mailchimp.com|credit-karma.com|creditkarma.com", "quickbooks|turbotax|mailchimp|credit karma", "finance"),
        b("ADP", "adp.com", "", "finance"),
        b("Coinbase", "coinbase.com", "", "finance"),
        b("Binance", "binance.com", "", "finance"),
        b("Kraken", "kraken.com", "", "finance"),
        b("Venmo", "venmo.com", "", "finance"),
        b("Cash App", "cash.app|squareup.com|square.com", "cashapp|square", "finance"),
        b("Zelle", "zellepay.com|zelle.com", "", "finance"),
        b("Stripe", "stripe.com", "", "finance"),
        b("Klarna", "klarna.com", "", "finance"),
        b("Affirm", "affirm.com", "", "finance"),
        b("Chase", "chase.com|jpmorgan.com|jpmchase.com|jpmorganchase.com", "jp morgan|jpmorgan|chase bank", "bank"),
        b("Bank of America", "bankofamerica.com|bofa.com|ml.com|merrill.com", "bankamerica|merrill lynch|merrill", "bank"),
        b("Wells Fargo", "wellsfargo.com|wf.com|wellsfargoadvisors.com", "wellsfargo|wells-fargo", "bank"),
        b("Citi", "citi.com|citibank.com|citigroup.com|citicards.com", "citibank|citigroup|citi cards", "bank"),
        b("Capital One", "capitalone.com|capitalone360.com|notification.capitalone.com", "capitalone|capital 1", "bank"),
        b("American Express", "americanexpress.com|aexp.com|amex.com|welcome.americanexpress.com", "amex", "bank"),
        b("Discover", "discover.com|discovercard.com", "discover card|discover bank", "bank"),
        b("U.S. Bank", "usbank.com", "us bank|usbank", "bank"),
        b("PNC Bank", "pnc.com", "pnc", "bank"),
        b("Truist", "truist.com|suntrust.com|bbt.com", "suntrust|bb&t", "bank"),
        b("TD Bank", "td.com|tdbank.com", "td bank|toronto dominion", "bank"),
        b("USAA", "usaa.com", "", "bank"),
        b("Navy Federal", "navyfederal.org|nfcu.org", "navy federal credit union|nfcu", "bank"),
        b("HSBC", "hsbc.com|hsbc.co.uk|us.hsbc.com", "", "bank"),
        b("Barclays", "barclays.co.uk|barclays.com|barclaycard.co.uk|barclaycardus.com", "barclaycard", "bank"),
        b("Lloyds Bank", "lloydsbank.co.uk|lloydsbank.com|halifax.co.uk", "lloyds|halifax", "bank"),
        b("NatWest", "natwest.com|rbs.co.uk|natwestgroup.com", "royal bank of scotland|rbs", "bank"),
        b("Santander", "santander.com|santander.co.uk|santanderbank.com", "", "bank"),
        b("Fidelity", "fidelity.com|fmr.com", "fidelity investments", "finance"),
        b("Charles Schwab", "schwab.com", "schwab", "finance"),
        b("Vanguard", "vanguard.com", "", "finance"),
        b("Robinhood", "robinhood.com", "", "finance"),
        b("Experian", "experian.com", "", "finance"),
        b("Equifax", "equifax.com", "", "finance"),
        b("TransUnion", "transunion.com", "trans union", "finance"),
        b("IRS", "irs.gov", "internal revenue service", "government"),
        b("Social Security Administration", "ssa.gov", "social security|ssa", "government"),
        b("Medicare", "medicare.gov|cms.gov|cms.hhs.gov", "", "government"),
        b("USPS", "usps.com|usps.gov|email.usps.com", "united states postal service|u.s. postal service|us postal service|postal service", "shipping"),
        b("UPS", "ups.com|upsemail.com", "united parcel service", "shipping"),
        b("FedEx", "fedex.com", "fed ex|federal express", "shipping"),
        b("DHL", "dhl.com|dhl.de|dhl.co.uk|dhlparcel.com|dhl-news.com", "dhl express", "shipping"),
        b("Royal Mail", "royalmail.com", "royalmail", "shipping"),
        b("Canada Post", "canadapost.ca|canadapost-postescanada.ca", "postes canada", "shipping"),
        b("Evri", "evri.com|hermes-europe.co.uk", "hermes", "shipping"),
        b("Walmart", "walmart.com|email.walmart.com|samsclub.com", "sam's club|sams club", "retail"),
        b("Target", "target.com|em.target.com", "", "retail"),
        b("Costco", "costco.com|online.costco.com", "", "retail"),
        b("The Home Depot", "homedepot.com|email.homedepot.com", "home depot|homedepot", "retail"),
        b("Lowe's", "lowes.com", "lowes", "retail"),
        b("eBay", "ebay.com|ebay.co.uk|reply.ebay.com", "e-bay", "retail"),
        b("Etsy", "etsy.com|mail.etsy.com", "", "retail"),
        b("Wayfair", "wayfair.com", "", "retail"),
        b("Temu", "temu.com|temuemail.com", "", "retail"),
        b("Shein", "shein.com|sheinemail.com", "", "retail"),
        b("AliExpress", "aliexpress.com|alibaba.com", "alibaba|ali express", "retail"),
        b("Kohl's", "kohls.com|email.kohls.com", "kohls", "retail"),
        b("Macy's", "macys.com|emails.macys.com", "macys", "retail"),
        b("CVS", "cvs.com|cvshealth.com", "cvs pharmacy|cvs health", "retail"),
        b("Walgreens", "walgreens.com|email.walgreens.com", "", "retail"),
        b("Kroger", "kroger.com", "", "retail"),
        b("Spotify", "spotify.com", "", "media"),
        b("Disney", "disney.com|disneyplus.com|disneyaccount.com|hulu.com|espn.com", "disney+|disney plus|hulu|espn", "media"),
        b("HBO / Max", "hbomax.com|max.com|hbo.com|warnermedia.com", "hbo|hbo max", "media"),
        b("Paramount+", "paramountplus.com|paramount.com|cbs.com", "paramount plus|paramount", "media"),
        b("Peacock", "peacocktv.com|nbcuni.com", "peacock tv", "media"),
        b("Roku", "roku.com", "", "media"),
        b("Verizon", "verizon.com|verizonwireless.com|vzw.com|verizon.net", "verizon wireless", "telecom"),
        b("AT&T", "att.com|att.net|e.att.com|attonline.com", "at&t|att wireless|at and t", "telecom"),
        b("T-Mobile", "t-mobile.com|tmobile.com|sprint.com", "tmobile|t mobile|sprint", "telecom"),
        b("Comcast / Xfinity", "comcast.com|comcast.net|xfinity.com", "comcast|xfinity", "telecom"),
        b("Spectrum", "spectrum.com|spectrum.net|charter.com|charter.net", "charter communications|charter", "telecom"),
        b("Cox", "cox.com|cox.net", "cox communications", "telecom"),
        b("BT", "bt.com|btinternet.com", "british telecom", "telecom"),
        b("Vodafone", "vodafone.com|vodafone.co.uk|vodafone.de", "", "telecom"),
        b("Samsung", "samsung.com|samsungusa.com", "", "tech"),
        b("Sony / PlayStation", "sony.com|playstation.com|sonyentertainmentnetwork.com|sie.com", "playstation|psn|sony", "tech"),
        b("Nintendo", "nintendo.com|nintendo.net", "", "tech"),
        b("Steam", "steampowered.com|valvesoftware.com", "valve", "tech"),
        b("Epic Games", "epicgames.com", "fortnite", "tech"),
        b("Dell", "dell.com", "", "tech"),
        b("HP", "hp.com", "hewlett packard|hewlett-packard", "tech"),
        b("Lenovo", "lenovo.com", "", "tech"),
        b("Uber", "uber.com", "uber eats|ubereats", "travel"),
        b("Lyft", "lyft.com|lyftmail.com", "", "travel"),
        b("DoorDash", "doordash.com", "door dash", "travel"),
        b("Grubhub", "grubhub.com", "", "travel"),
        b("Instacart", "instacart.com", "", "travel"),
        b("Airbnb", "airbnb.com", "air bnb", "travel"),
        b("Booking.com", "booking.com", "booking", "travel"),
        b("Expedia", "expedia.com|expediamail.com|hotels.com|vrbo.com", "hotels.com|vrbo", "travel"),
        b("Delta Air Lines", "delta.com|e.delta.com", "delta airlines|delta air lines|delta", "travel"),
        b("American Airlines", "aa.com|email.aa.com|aadvantage.com", "aadvantage", "travel"),
        b("United Airlines", "united.com|news.united.com", "united mileageplus|mileageplus", "travel"),
        b("Southwest Airlines", "southwest.com|luv.southwest.com", "southwest", "travel"),
        b("Marriott", "marriott.com|email-marriott.com|marriottbonvoy.com", "marriott bonvoy|bonvoy", "travel"),
        b("Hilton", "hilton.com|hiltonhonors.com", "hilton honors", "travel"),
        b("Ring", "ring.com", "", "tech"),
        b("Twilio", "twilio.com|sendgrid.com|sendgrid.net", "sendgrid", "tech"),
        b("Proton", "proton.me|protonmail.com", "protonmail|proton mail", "tech"),
        b("Zoho", "zoho.com|zohomail.com", "", "tech"),
        b("Fastmail", "fastmail.com|fastmail.fm", "", "tech"),
        b("Mozilla", "mozilla.org|mozilla.com|thunderbird.net", "firefox|thunderbird", "tech"),
        b("Cloudflare", "cloudflare.com", "", "tech"),
        b("Twitch", "twitch.tv", "", "media"),
        b("Kaspersky", "kaspersky.com", "", "security"),
        b("Avast", "avast.com|avg.com", "avg", "security"),
        b("Malwarebytes", "malwarebytes.com", "", "security"),
        b("Bitdefender", "bitdefender.com", "", "security"),
        b("LastPass", "lastpass.com", "", "security"),
        b("1Password", "1password.com", "", "security"),
        b("Chime", "chime.com", "", "finance"),
        b("SoFi", "sofi.com|sofi.org", "", "finance"),
        b("Ally Bank", "ally.com", "ally", "bank"),
        b("Synchrony", "synchrony.com|synchronybank.com|syf.com", "synchrony bank", "bank"),
        b("Regions Bank", "regions.com", "regions", "bank"),
        b("Fifth Third Bank", "53.com", "fifth third", "bank"),
        b("Huntington", "huntington.com", "huntington bank", "bank"),
        b("Citizens Bank", "citizensbank.com|citizensone.com", "citizens", "bank"),
        b("KeyBank", "key.com|keybank.com", "key bank", "bank"),
        b("M&T Bank", "mtb.com", "m&t", "bank"),
        b("Western Union", "westernunion.com|wu.com", "westernunion", "finance"),
        b("MoneyGram", "moneygram.com", "money gram", "finance"),
        b("Publishers Clearing House", "pch.com", "pch", "misc"),
        b("Costco Travel", "costcotravel.com", "", "travel"),
        b("Ticketmaster", "ticketmaster.com|livenation.com", "live nation", "media"),
        b("StubHub", "stubhub.com", "", "media"),
        b("GEICO", "geico.com", "", "insurance"),
        b("State Farm", "statefarm.com", "statefarm", "insurance"),
        b("Progressive", "progressive.com|email.progressive.com", "progressive insurance", "insurance"),
        b("Allstate", "allstate.com", "", "insurance"),
        b("Liberty Mutual", "libertymutual.com", "", "insurance"),
        b("Aetna", "aetna.com", "", "insurance"),
        b("UnitedHealthcare", "uhc.com|unitedhealthgroup.com|optum.com", "united healthcare|unitedhealth|optum", "insurance"),
        b("Blue Cross Blue Shield", "bcbs.com|anthem.com|elevancehealth.com", "bluecross|blue cross|anthem", "insurance"),
        b("Cigna", "cigna.com", "", "insurance"),
        b("Humana", "humana.com", "", "insurance"),
        b("Kaiser Permanente", "kp.org|kaiserpermanente.org", "kaiser", "insurance"),
        b("Ancestry", "ancestry.com|ancestry.co.uk", "ancestry.com|ancestrydna", "misc"),
        b("MyHeritage", "myheritage.com", "my heritage", "misc"),
        b("23andMe", "23andme.com", "", "misc"),
        b("FamilySearch", "familysearch.org", "family search", "misc"),
        b("Indeed", "indeed.com", "", "misc"),
        b("Glassdoor", "glassdoor.com", "", "misc"),
        b("ZipRecruiter", "ziprecruiter.com", "zip recruiter", "misc"),
        b("Monster", "monster.com", "", "misc"),
        b("Craigslist", "craigslist.org", "", "misc"),
        b("Nextdoor", "nextdoor.com|nextdoor.co.uk", "", "misc"),
        b("Grammarly", "grammarly.com", "", "tech"),
        b("Canva", "canva.com", "", "tech"),
        b("Evernote", "evernote.com", "", "tech"),
        b("Notion", "notion.so|makenotion.com", "", "tech"),
        b("OpenAI", "openai.com", "chatgpt", "tech"),
        b("Anthropic", "anthropic.com|claude.ai", "claude", "tech"),
        b("Duolingo", "duolingo.com", "", "misc"),
        b("Coursera", "coursera.org", "", "misc"),
        b("Udemy", "udemy.com", "", "misc"),
        b("WordPress", "wordpress.com|wordpress.org|automattic.com", "word press", "tech"),
        b("Bluehost", "bluehost.com", "", "tech"),
        b("HostGator", "hostgator.com", "", "tech"),
        b("IONOS", "ionos.com|ionos.co.uk|1and1.com", "1&1|1and1", "tech"),
        b("Chick-fil-A", "chick-fil-a.com", "chickfila", "retail"),
        b("McDonald's", "mcdonalds.com", "mcdonalds", "retail"),
        b("Starbucks", "starbucks.com", "", "retail"),
        b("Dunkin'", "dunkindonuts.com|dunkin.com", "dunkin", "retail"),
        b("Tesla", "tesla.com", "", "misc"),
        b("Toyota", "toyota.com", "", "misc"),
        b("Ford", "ford.com", "", "misc"),
        b("GM", "gm.com|onstar.com|chevrolet.com", "general motors|onstar|chevrolet|chevy", "misc"),
        b("Carvana", "carvana.com", "", "misc"),
        b("Autotrader", "autotrader.com", "auto trader", "misc"),
        b("AAA", "aaa.com|ace.aaa.com", "american automobile association", "misc"),
        b("Costco Wholesale", "costco.com", "", "retail"),
        b("Tesco", "tesco.com|tescobank.com", "", "retail"),
        b("Sainsbury's", "sainsburys.co.uk", "sainsburys", "retail"),
        b("ASDA", "asda.com|asda.co.uk", "", "retail"),
        b("Argos", "argos.co.uk", "", "retail"),
        b("John Lewis", "johnlewis.com|johnlewispartnership.co.uk", "", "retail"),
        b("Currys", "currys.co.uk", "", "retail"),
        b("HMRC", "hmrc.gov.uk|gov.uk", "hm revenue & customs|hm revenue and customs|revenue and customs", "government"),
        b("DVLA", "dvla.gov.uk|gov.uk", "driver and vehicle licensing", "government"),
        b("NHS", "nhs.uk|nhs.net", "national health service", "government"),
        b("Australian Taxation Office", "ato.gov.au", "ato", "government"),
        b("Canada Revenue Agency", "canada.ca|cra-arc.gc.ca", "cra", "government"),
        b("Interac", "interac.ca", "interac e-transfer", "finance"),
        b("RBC", "rbc.com|rbcroyalbank.com", "royal bank of canada|rbc royal bank", "bank"),
        b("Scotiabank", "scotiabank.com", "scotia bank|scotia", "bank"),
        b("BMO", "bmo.com", "bank of montreal", "bank"),
        b("CIBC", "cibc.com", "", "bank"),
        b("Commonwealth Bank", "commbank.com.au|cba.com.au", "commbank|cba", "bank"),
        b("ANZ", "anz.com|anz.com.au", "", "bank"),
        b("Westpac", "westpac.com.au", "", "bank"),
        b("NAB", "nab.com.au", "national australia bank", "bank"),
        b("Deutsche Bank", "db.com|deutsche-bank.de", "", "bank"),
        b("ING", "ing.com|ing.nl|ing.de|ing.be", "ing bank|ing direct", "bank"),
        b("Revolut", "revolut.com", "", "finance"),
        b("Wise", "wise.com|transferwise.com", "transferwise", "finance"),
        b("Monzo", "monzo.com", "", "bank"),
        b("Starling Bank", "starlingbank.com", "starling", "bank"),
        b("N26", "n26.com", "", "bank")
    )

    /**
     * All known organisations: the core list first, then the extended table, skipping any
     * extended entry whose name or domains are already covered by a core entry.
     */
    val brands: List<Brand> = buildList {
        addAll(coreBrands)
        val names = coreBrands.map { it.name.lowercase() }.toHashSet()
        val domains = coreBrands.flatMap { it.domains }.toHashSet()
        for (extra in BrandKnowledgeBaseExtra.brands) {
            if (extra.name.lowercase() in names) continue
            val fresh = extra.domains.filter { it !in domains }
            if (fresh.isEmpty()) continue
            add(if (fresh.size == extra.domains.size) extra else extra.copy(domains = fresh))
            names += extra.name.lowercase(); domains += fresh
        }
    }

    /** Consumer webmail / freemail domains. A brand mailing from one of these is a red flag. */
    val freemailDomains: Set<String> = setOf(
        "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.uk", "yahoo.ca", "yahoo.fr", "yahoo.de", "ymail.com",
        "rocketmail.com", "outlook.com", "hotmail.com", "hotmail.co.uk", "hotmail.fr", "live.com", "live.co.uk",
        "msn.com", "aol.com", "icloud.com", "me.com", "mac.com", "protonmail.com", "proton.me", "pm.me",
        "gmx.com", "gmx.de", "gmx.net", "web.de", "mail.com", "mail.ru", "yandex.com", "yandex.ru", "zoho.com",
        "zohomail.com", "fastmail.com", "tutanota.com", "tuta.io", "hushmail.com", "inbox.com", "email.com",
        "usa.com", "comcast.net", "att.net", "verizon.net", "sbcglobal.net", "bellsouth.net", "cox.net",
        "charter.net", "earthlink.net", "juno.com", "netzero.net", "optonline.net", "btinternet.com",
        "sky.com", "virginmedia.com", "talktalk.net", "ntlworld.com", "rediffmail.com", "qq.com", "163.com",
        "126.com", "sina.com", "naver.com", "daum.net", "hanmail.net", "seznam.cz", "wp.pl", "onet.pl",
        "libero.it", "virgilio.it", "orange.fr", "wanadoo.fr", "free.fr", "laposte.net", "sfr.fr", "t-online.de",
        "freenet.de", "bluewin.ch", "telenet.be", "skynet.be", "ziggo.nl", "kpnmail.nl", "shaw.ca", "rogers.com",
        "sympatico.ca", "bigpond.com", "optusnet.com.au", "xtra.co.nz"
    )

    /**
     * Shared bulk-mail infrastructure domains. Many unrelated senders use these, so a rule should
     * key on the full address rather than the domain when mail arrives through one of them.
     */
    val sharedSendingDomains: Set<String> = freemailDomains + setOf(
        "amazonses.com", "sendgrid.net", "sendgrid.com", "mailchimp.com", "mcsv.net", "mailgun.org", "mailgun.net",
        "sparkpostmail.com", "mandrillapp.com", "constantcontact.com", "rsgsv.net", "cmail19.com", "cmail20.com",
        "createsend.com", "hubspotemail.net", "hubspot.com", "klaviyomail.com", "sendinblue.com", "brevo.com",
        "mailjet.com", "postmarkapp.com", "mailerlite.com", "getresponse.com", "aweber.com", "salesforce.com",
        "exacttarget.com", "substack.com", "beehiiv.com", "convertkit.com", "ccsend.com"
    )

    /** Words that make a display name look like an organisation rather than a person. */
    val organisationWords: Set<String> = setOf(
        "team", "support", "service", "services", "billing", "security", "account", "accounts", "notification",
        "notifications", "alert", "alerts", "department", "dept", "customer", "care", "helpdesk", "help",
        "desk", "admin", "administrator", "office", "payments", "payment", "verification", "verify", "center",
        "centre", "bank", "banking", "official", "wallet", "delivery", "shipping", "rewards", "reward", "prize",
        "winner", "lottery", "claims", "claim", "refund", "refunds", "invoice", "invoices", "order", "orders",
        "hr", "payroll", "it", "noreply", "no-reply", "donotreply", "mailer", "info", "update", "updates",
        "membership", "subscription", "renewal", "inc", "llc", "ltd", "corp", "corporation", "company", "co",
        "group", "online", "store", "shop", "pharmacy", "clinic", "insurance", "loans", "credit", "mortgage",
        "tax", "revenue", "government", "gov", "agency", "federal", "national", "postal", "express", "courier"
    )

    /** Tokens in an organisation name that carry no identity of their own. */
    val nameStopwords: Set<String> = organisationWords + setOf(
        "the", "of", "and", "for", "your", "our", "from", "via", "at", "in", "on", "by", "to", "a", "an", "is",
        "new", "my", "me", "you", "us", "com", "net", "org", "email", "mail", "message", "messages", "news",
        "newsletter", "daily", "weekly"
    )

    private val byAlias: Map<String, Brand> = buildMap {
        for (brand in brands) for (alias in brand.aliases) putIfAbsent(alias, brand)
    }

    /** Aliases sorted longest first so "bank of america" wins over "america". */
    val aliasesLongestFirst: List<Pair<String, Brand>> =
        byAlias.entries.map { it.key to it.value }.sortedByDescending { it.first.length }

    /**
     * Labels that are ordinary words or shared infrastructure names; never used to decide that a
     * domain "imitates" a brand (a display-name claim is still detected separately).
     */
    val ambiguousLabels: Set<String> = setOf(
        "mail", "email", "news", "info", "accounts", "account", "login", "secure", "notification", "notifications",
        "united", "delta", "target", "discover", "progressive", "square", "monster", "indeed", "wise", "chime", "affirm",
        "steam", "peacock", "spectrum", "regions", "citizens", "huntington", "windows", "office", "meta", "cash", "ford",
        "notion", "starling", "halifax", "ring", "ally", "key", "epic", "max", "wells", "bank", "capital", "one",
        "american", "express", "royal", "national", "first", "state", "farm", "liberty", "mutual", "general", "motors",
        "home", "depot", "best", "buy", "geek", "squad", "family", "search", "book", "club", "live", "me", "mac", "apple",
        "amazon", "google", "microsoft", "prime", "video", "play", "store", "pay", "hotels", "booking", "canada", "post",
        "revenue", "agency", "postal", "service", "customs", "tax", "social", "security", "medicare", "internal", "gov"
    ).minus(setOf("apple", "amazon", "google", "microsoft"))   // these four are worth flagging even though they are words

    /**
     * One-word organisation names that are also ordinary words or first names. They only count
     * as a claim when they are the whole display name or appear with an organisation word.
     */
    val ambiguousAliases: Set<String> = ambiguousLabels + setOf(
        "act", "ana", "ap", "au", "box", "budget", "carrier", "coach", "compass", "constellation", "continental", "corona",
        "current", "dave", "discovery", "dodge", "dover", "express", "fortune", "genesis", "gemini", "hermes", "hey", "honor",
        "huntington", "jaguar", "jared", "jumbo", "king", "lincoln", "line", "marcus", "mars", "max", "medium", "mega",
        "mini", "mosaic", "next", "nice", "noon", "nu", "omega", "opera", "orange", "pandora", "peacock", "penny", "prosper",
        "purple", "railway", "ram", "ramp", "render", "sanity", "saturn", "seat", "sharp", "shell", "slack", "spectrum",
        "square", "stash", "subway", "swift", "target", "tide", "total", "travelers", "vans", "very", "vi", "visible",
        "vogue", "wired", "mit", "va", "who", "sec", "ets", "ird", "ee", "db", "ge", "tim", "eff", "ford", "drift", "buffer",
        "hover", "ledger", "elastic", "plaid", "brave", "upstart", "affinity", "block", "clover", "paramount", "liverpool",
        "providence", "general", "liberty", "national", "guardian", "principal", "equitable", "empower", "globe", "root",
        "hippo", "lemonade", "casper", "weber", "stanley", "yeti", "cricket", "mint", "boost", "sky", "three", "virgin",
        "bell", "rogers", "shaw", "spark", "origin", "aqua", "essential", "evergreen", "pioneer", "devon", "apache", "hess",
        "williams", "plains", "enterprise", "alliance", "unity", "epic", "humble", "hero", "dash", "meta", "apple", "amazon",
        "google", "microsoft", "chase", "discover", "progressive", "delta", "united", "monster", "indeed", "wise", "chime",
        "affirm", "steam", "regions", "citizens", "windows", "office", "cash", "notion", "starling", "halifax", "ring",
        "ally", "key", "wells", "first", "state", "farm", "home", "depot", "best", "buy", "family", "search", "book", "club",
        "live", "me", "mac", "prime", "video", "play", "store", "pay", "hotels", "booking", "post", "revenue", "agency",
        "postal", "service", "customs", "tax", "social", "security", "medicare", "internal", "gov", "mail", "email", "news",
        "info", "accounts", "account", "login", "secure", "notification", "notifications", "cbs", "abc", "fox", "sun",
        "tesco", "boots", "next", "iceland", "coop", "argos", "range", "very", "loft", "gap", "express", "carters",
        "scholastic", "pearson", "wiley", "elsevier", "act", "college", "board", "common", "app", "canvas", "moodle"
    )

    /** Words spammers bolt onto a brand name inside a domain ("paypal-secure-login"). */
    val decorationWords: Set<String> = setOf(
        "secure", "security", "login", "signin", "sign", "support", "service", "services", "help", "helpdesk", "billing",
        "account", "accounts", "verify", "verification", "verified", "update", "updates", "alert", "alerts", "team",
        "mail", "email", "online", "official", "center", "centre", "customer", "customers", "care", "pay", "payment",
        "payments", "my", "the", "get", "app", "apps", "id", "notice", "notices", "info", "web", "net", "cloud", "portal",
        "access", "auth", "safe", "safety", "protect", "protection", "wallet", "bank", "banking", "card", "cards",
        "delivery", "deliveries", "parcel", "package", "tracking", "track", "shipment", "shipping", "refund", "refunds",
        "reward", "rewards", "prize", "claim", "claims", "order", "orders", "shop", "store", "deals", "promo", "gift",
        "gifts", "member", "members", "membership", "renew", "renewal", "invoice", "invoices", "resolution", "dispute",
        "limited", "restricted", "suspended", "unlock", "recovery", "recover", "reset", "password", "confirm", "confirmation",
        "us", "uk", "ca", "au", "eu", "com", "net", "org", "inc", "corp", "group", "global", "int", "intl", "en", "hd"
    )

    /** Brands from the hand-curated core list (typo-squat detection is limited to these). */
    val coreBrandNames: Set<String> = coreBrands.map { it.name }.toHashSet()

    /** Distinct labels used for look-alike detection ("paypal", "wellsfargo"…), longest first. */
    val brandLabels: List<Pair<String, Brand>> = buildList {
        val seen = HashSet<String>()
        for (brand in brands) {
            val labels = LinkedHashSet<String>()
            brand.domains.forEach { d -> labels.add(HeaderParser.registrableDomain(d).substringBefore('.')) }
            brand.aliases.forEach { a -> if (!a.contains('.')) labels.add(a.replace(Regex("[^a-z0-9]"), "")) }
            labels.filter { it.length >= 4 && it !in ambiguousLabels && it !in nameStopwords && seen.add(it) }
                .forEach { add(it to brand) }
        }
    }.sortedByDescending { it.first.length }

    fun findByAlias(alias: String): Brand? = byAlias[alias.lowercase()]

    fun isFreemail(domain: String): Boolean = HeaderParser.registrableDomain(domain) in freemailDomains

    fun isSharedSender(domain: String): Boolean = HeaderParser.registrableDomain(domain) in sharedSendingDomains

    /** True if [domain] is, or is a subdomain of, one of the brand's legitimate domains. */
    fun domainBelongsTo(domain: String, brand: Brand): Boolean {
        val d = domain.lowercase()
        return brand.domains.any { legit -> d == legit || d.endsWith(".$legit") }
    }

    /** All brands that legitimately own [domain]. */
    fun brandsForDomain(domain: String): List<Brand> = brands.filter { domainBelongsTo(domain, it) }
}
