package info.socrtwo.quillpost.web.spam

/**
 * Extended organisation table (generated from the brands-N.tsv files under web/src/main/resources/brands by
 * scripts/gen-brands.py — edit the TSV files, not this file). Each line is
 * name|domains|aliases|category. Domains were checked to resolve in DNS when generated.
 */
object BrandKnowledgeBaseExtra {

    private const val CHUNK_0 = """
Walmart|walmart.ca,walmart.com,walmartmoneycard.com|Wal-Mart|retail
Amazon.com Inc|amazon.ca,amazon.co.jp,amazon.co.uk,amazon.com,amazon.de,amazon.in|Amazon|retail
Apple Inc|apple.com,icloud.com|Apple|tech
UnitedHealth Group|optum.com,uhc.com,unitedhealthgroup.com|UnitedHealthcare,Optum|insurance
CVS Health|aetna.com,caremark.com,cvs.com,cvshealth.com|CVS,CVS Caremark|retail
Berkshire Hathaway|berkshirehathaway.com,brk.com||finance
ExxonMobil|exxon.com,exxonmobil.com,mobil.com|Exxon,Mobil|energy
Alphabet|abc.xyz,google.com|Google|tech
McKesson|mckesson.com||healthcare
Cencora|amerisourcebergen.com,cencora.com|AmerisourceBergen|healthcare
Costco Wholesale|costco.ca,costco.co.uk,costco.com|Costco|retail
JPMorgan Chase|chase.com,jpmorgan.com,jpmorganchase.com|JP Morgan,Chase|bank
Microsoft Corporation|microsoft.com|Microsoft|tech
Cardinal Health|cardinalhealth.com||healthcare
Chevron|chevron.com,chevrontexacocards.com|Texaco|energy
Cigna Group|cigna.com,evernorth.com,expressscripts.com|Cigna,Express Scripts|insurance
Ford Motor Company|ford.com,fordcredit.com,lincoln.com|Ford,Lincoln|auto
Bank of America Corporation|bankofamerica.com,bofa.com,merrilledge.com|Bank of America|bank
General Motors|buick.com,cadillac.com,chevrolet.com,gm.com,gmc.com,gmfinancial.com,onstar.com|Chevrolet,Cadillac,Buick,GMC,GM Financial|auto
Elevance Health|anthem.com,carelon.com,elevancehealth.com|Anthem,Carelon|insurance
Citigroup|citi.com,citibank.com,citigroup.com|Citibank,Citi|bank
Centene|ambetterhealth.com,centene.com,wellcare.com|Ambetter,WellCare|insurance
Home Depot|homedepot.com||retail
Marathon Petroleum|marathonpetroleum.com,speedway.com|Speedway|energy
Kroger|fredmeyer.com,frysfood.com,harristeeter.com,kingsoopers.com,kroger.com,ralphs.com,smithsfoodanddrug.com|Ralphs,Fred Meyer,King Soopers,Fry's Food,Harris Teeter|retail
Phillips 66|76.com,conoco.com,phillips66.com||energy
Fannie Mae|fanniemae.com||finance
Walgreens Boots Alliance|boots.com,duanereade.com,walgreens.com,walgreensbootsalliance.com|Walgreens,Boots|retail
Valero Energy|valero.com|Valero|energy
Meta Platforms|facebook.com,facebookmail.com,fb.com,instagram.com,meta.com,whatsapp.com|Meta,Facebook|tech
Verizon Communications|verizon.com,verizonwireless.com,vzw.com|Verizon|telecom
AT&T Inc|att.com,att.net|AT&T|telecom
Comcast|comcast.com,comcast.net,nbcuni.com,peacocktv.com,xfinity.com|Xfinity,NBCUniversal|telecom
Wells Fargo & Company|wellsfargo.com,wf.com|Wells Fargo|bank
Goldman Sachs|goldmansachs.com,gs.com,marcus.com|Marcus by Goldman Sachs|bank
Freddie Mac|freddiemac.com||finance
Target Corporation|target.com|Target|retail
Humana|humana.com||insurance
State Farm|statefarm.com||insurance
Tesla|tesla.com||auto
Morgan Stanley|etrade.com,morganstanley.com|E*TRADE|bank
Johnson & Johnson|janssen.com,jnj.com,kenvue.com|J&J,Janssen,Kenvue|pharma
Archer Daniels Midland|adm.com|ADM|agriculture
PepsiCo|fritolay.com,gatorade.com,pepsi.com,pepsico.com,quakeroats.com|Pepsi,Frito-Lay,Quaker Oats,Gatorade|consumer
United Parcel Service|ups.com|UPS|shipping
FedEx Corporation|fedex.com|FedEx|shipping
Dell Technologies|dell.com,delltechnologies.com|Dell|tech
MetLife|metlife.com||insurance
Lowe's Companies|lowes.com|Lowe's|retail
Energy Transfer|energytransfer.com||energy
Boeing|boeing.com||aerospace
Albertsons|acmemarkets.com,albertsons.com,jewelosco.com,safeway.com,shaws.com,vons.com|Safeway,Vons,Jewel-Osco,Acme Markets,Shaw's|retail
Sysco|sysco.com||food
RTX Corporation|collinsaerospace.com,prattwhitney.com,raytheon.com,rtx.com|Raytheon,Pratt & Whitney,Collins Aerospace|aerospace
General Dynamics|gd.com,gdit.com,gulfstream.com|Gulfstream|aerospace
Lockheed Martin|lmco.com,lockheedmartin.com||aerospace
AbbVie|abbvie.com,allergan.com|Allergan|pharma
Prudential Financial|prudential.com||insurance
Caterpillar|cat.com,caterpillar.com||industrial
Pfizer|pfizer.com||pharma
Progressive Corporation|progressive.com|Progressive Insurance|insurance
HCA Healthcare|hcahealthcare.com||healthcare
IBM|ibm.com,redhat.com|International Business Machines,Red Hat|tech
Deere & Company|deere.com,johndeere.com|John Deere|industrial
Merck & Co|merck.com,msd.com|Merck,MSD|pharma
Intel Corporation|intel.com|Intel|tech
Nike|converse.com,jordan.com,nike.com|Converse|consumer
Allstate|allstate.com,esurance.com|Esurance|insurance
Publix|publix.com||retail
TJX Companies|homegoods.com,marshalls.com,sierra.com,tjmaxx.com,tjx.com,winners.ca|TJ Maxx,Marshalls,HomeGoods|retail
Enterprise Mobility|alamo.com,enterprise.com,enterpriseholdings.com,nationalcar.com|Enterprise Rent-A-Car,Alamo,National Car Rental|travel
Tyson Foods|tyson.com,tysonfoods.com||food
Nvidia|nvidia.com|NVIDIA|tech
American Express Company|americanexpress.com,amex.com|American Express|bank
Charter Communications|charter.com,spectrum.com,spectrum.net|Spectrum|telecom
Oracle Corporation|netsuite.com,oracle.com|Oracle,NetSuite|tech
Cisco Systems|cisco.com,meraki.com,webex.com|Cisco,Webex|tech
Delta Air Lines|delta.com||travel
American Airlines Group|aa.com|American Airlines|travel
United Airlines Holdings|united.com|United Airlines|travel
Nationwide Mutual|nationwide.com||insurance
Coca-Cola Company|coca-cola.com,coca-colacompany.com,coke.com|Coca-Cola,Coke|consumer
Liberty Mutual Insurance|libertymutual.com,safeco.com|Liberty Mutual,Safeco|insurance
Best Buy|bestbuy.com,geeksquad.com|Geek Squad|retail
Dow Inc|dow.com|Dow Chemical|chemicals
ConocoPhillips|conocophillips.com||energy
Northrop Grumman|ngc.com,northropgrumman.com||aerospace
Abbott Laboratories|abbott.com|Abbott|healthcare
Bristol Myers Squibb|bms.com||pharma
Thermo Fisher Scientific|thermofisher.com||healthcare
Honeywell|honeywell.com||industrial
Qualcomm|qualcomm.com||tech
Starbucks|starbucks.com||retail
Travelers Companies|travelers.com|Travelers|insurance
USAA|usaa.com||insurance
3M|3m.com||industrial
Capital One Financial|capitalone.com|Capital One|bank
Broadcom|broadcom.com,vmware.com|VMware|tech
Dollar General|dg.com,dollargeneral.com||retail
Eli Lilly|lilly.com|Lilly|pharma
Salesforce|mulesoft.com,salesforce.com,slack.com,tableau.com|Slack,Tableau|tech
PayPal Holdings|paypal.com,venmo.com|PayPal,Venmo|finance
Danaher|danaher.com||healthcare
Dollar Tree|dollartree.com,familydollar.com|Family Dollar|retail
Exelon|bge.com,comed.com,exeloncorp.com,peco.com|ComEd,PECO,BGE|utilities
Duke Energy|duke-energy.com||utilities
Southern Company|alabamapower.com,georgiapower.com,southerncompany.com|Georgia Power,Alabama Power|utilities
Mondelez International|mondelezinternational.com,oreo.com|Mondelez,Oreo|consumer
Kraft Heinz|kraftheinz.com,kraftheinzcompany.com|Kraft,Heinz|consumer
Amgen|amgen.com||pharma
CHS Inc|chsinc.com||agriculture
Coupang|coupang.com||retail
Applied Materials|appliedmaterials.com||tech
Nucor|nucor.com||industrial
Advanced Micro Devices|amd.com|AMD|tech
Paccar|kenworth.com,paccar.com,peterbilt.com|Kenworth,Peterbilt|auto
Lennar|lennar.com||construction
D.R. Horton|drhorton.com||construction
Uber Technologies|uber.com|Uber|tech
Micron Technology|crucial.com,micron.com|Micron,Crucial|tech
Arrow Electronics|arrow.com||tech
Netflix Inc|netflix.com|Netflix|media
Molina Healthcare|molinahealthcare.com||insurance
Performance Food Group|pfgc.com||food
US Foods|usfoods.com||food
Tenet Healthcare|tenethealth.com||healthcare
CBRE Group|cbre.com||realestate
Cummins|cummins.com||industrial
Jabil|jabil.com||tech
Halliburton|halliburton.com||energy
Occidental Petroleum|oxy.com|Occidental|energy
Union Pacific|up.com|Union Pacific Railroad|transport
CSX Corporation|csx.com|CSX|transport
Norfolk Southern|nscorp.com||transport
Macy's Inc|bloomingdales.com,macys.com|Macy's,Bloomingdale's|retail
Nordstrom|nordstrom.com,nordstromrack.com||retail
Gap Inc|athleta.com,bananarepublic.com,gap.com,oldnavy.com|Old Navy,Banana Republic,Athleta|retail
Ross Stores|rossstores.com||retail
Burlington Stores|burlington.com||retail
Kohl's Corporation|kohls.com|Kohl's|retail
Dick's Sporting Goods|dickssportinggoods.com|Dick's|retail
AutoZone|autozone.com||retail
O'Reilly Automotive|oreillyauto.com|O'Reilly Auto Parts|retail
Advance Auto Parts|advanceautoparts.com||retail
Genuine Parts Company|genpt.com,napaonline.com|NAPA Auto Parts|retail
Tractor Supply|tractorsupply.com||retail
Ulta Beauty|ulta.com||retail
Bath & Body Works|bathandbodyworks.com||retail
Victoria's Secret|victoriassecret.com||retail
Williams-Sonoma|potterybarn.com,westelm.com,williams-sonoma.com|Pottery Barn,West Elm|retail
RH|rh.com|Restoration Hardware|retail
Wayfair Inc|wayfair.com|Wayfair|retail
Chewy|chewy.com||retail
Petco|petco.com||retail
PetSmart|petsmart.com||retail
Foot Locker|footlocker.com||retail
Academy Sports|academy.com||retail
Big Lots|biglots.com||retail
Five Below|fivebelow.com||retail
BJ's Wholesale Club|bjs.com||retail
Sam's Club|samsclub.com||retail
Meijer|meijer.com||retail
H-E-B|heb.com||retail
Wegmans|wegmans.com||retail
Giant Eagle|gianteagle.com||retail
Hy-Vee|hy-vee.com||retail
WinCo Foods|wincofoods.com||retail
Aldi US|aldi.com,aldi.us|ALDI|retail
Lidl US|lidl.co.uk,lidl.com,lidl.de|Lidl|retail
Trader Joe's|traderjoes.com||retail
Whole Foods Market|wholefoodsmarket.com|Whole Foods|retail
Sprouts Farmers Market|sprouts.com||retail
Rite Aid|riteaid.com||retail
Menards|menards.com||retail
Ace Hardware|acehardware.com||retail
Harbor Freight Tools|harborfreight.com||retail
Michaels|michaels.com||retail
Hobby Lobby|hobbylobby.com||retail
JOANN|joann.com||retail
Staples|staples.com||retail
Office Depot|odpcorp.com,officedepot.com|OfficeMax|retail
Barnes & Noble|barnesandnoble.com||retail
GameStop|gamestop.com||retail
Sephora|sephora.com||retail
Nike Direct|nike.com||consumer
QVC|hsn.com,qurateretail.com,qvc.com|HSN|retail
Overstock|beyond.com,overstock.com|Bed Bath & Beyond|retail
Newegg|newegg.com||retail
Zappos|zappos.com||retail
Poshmark|poshmark.com||retail
Mercari|mercari.com||retail
StockX|stockx.com||retail
Rakuten|rakuten.co.jp,rakuten.com||retail
Groupon|groupon.com||retail
Instacart|instacart.com||tech
DoorDash Inc|doordash.com|DoorDash|tech
Grubhub|grubhub.com||tech
Postmates|postmates.com||tech
Lyft Inc|lyft.com|Lyft|tech
Airbnb Inc|airbnb.com|Airbnb|travel
Booking Holdings|agoda.com,booking.com,bookingholdings.com,kayak.com,opentable.com,priceline.com|Priceline,Kayak,Agoda,OpenTable|travel
Expedia Group|expedia.com,expediagroup.com,hotels.com,orbitz.com,travelocity.com,vrbo.com|Orbitz,Travelocity|travel
Tripadvisor|tripadvisor.com,viator.com|Viator|travel
Marriott International|marriott.com,marriottbonvoy.com|Marriott|travel
Hilton Worldwide|hilton.com,hiltonhonors.com|Hilton|travel
Hyatt Hotels|hyatt.com|Hyatt|travel
IHG Hotels & Resorts|holidayinn.com,ihg.com|IHG,Holiday Inn|travel
Wyndham Hotels|wyndham.com,wyndhamhotels.com|Wyndham|travel
Choice Hotels|choicehotels.com||travel
Best Western|bestwestern.com||travel
Accor|accor.com,all.accor.com|ALL Accor|travel
Carnival Corporation|carnival.com,hollandamerica.com,princess.com|Carnival Cruise Line,Princess Cruises,Holland America|travel
Royal Caribbean Group|celebritycruises.com,royalcaribbean.com|Royal Caribbean,Celebrity Cruises|travel
Norwegian Cruise Line|ncl.com||travel
Southwest Airlines Co|southwest.com|Southwest Airlines|travel
JetBlue Airways|jetblue.com|JetBlue|travel
Alaska Airlines|alaskaair.com||travel
Spirit Airlines|spirit.com||travel
Frontier Airlines|flyfrontier.com||travel
Hawaiian Airlines|hawaiianairlines.com||travel
Allegiant Air|allegiantair.com||travel
Air Canada|aircanada.com||travel
WestJet|westjet.com||travel
British Airways|ba.com,britishairways.com||travel
Lufthansa|lufthansa.com||travel
Air France|airfrance.com,airfrance.fr||travel
KLM|klm.com||travel
Ryanair|ryanair.com||travel
easyJet|easyjet.com||travel
Emirates|emirates.com||travel
Qatar Airways|qatarairways.com||travel
Etihad Airways|etihad.com||travel
Singapore Airlines|singaporeair.com||travel
Cathay Pacific|cathaypacific.com||travel
Qantas|qantas.com||travel
Virgin Atlantic|virginatlantic.com||travel
Turkish Airlines|turkishairlines.com||travel
Aer Lingus|aerlingus.com||travel
Iberia|iberia.com||travel
Japan Airlines|jal.co.jp,jal.com|JAL|travel
All Nippon Airways|ana.co.jp|ANA|travel
Korean Air|koreanair.com||travel
LATAM Airlines|latam.com,latamairlines.com|LATAM|travel
Avianca|avianca.com||travel
Aeromexico|aeromexico.com||travel
Copa Airlines|copaair.com||travel
Amtrak|amtrak.com||travel
Greyhound|greyhound.com||travel
Hertz|dollar.com,hertz.com,thrifty.com|Dollar Rent A Car,Thrifty|travel
Avis Budget Group|avis.com,avisbudgetgroup.com,budget.com|Avis,Budget|travel
Turo|turo.com||travel
Sixt|sixt.com,sixt.de||travel
Europcar|europcar.com||travel
Verizon Business|verizon.com||telecom
T-Mobile US|t-mobile.com,tmobile.com|T-Mobile|telecom
Lumen Technologies|centurylink.com,lumen.com|CenturyLink|telecom
Frontier Communications|frontier.com||telecom
Altice USA|alticeusa.com,optimum.com,optimum.net|Optimum|telecom
Cox Communications|cox.com,cox.net|Cox|telecom
Dish Network|boostmobile.com,dish.com|Boost Mobile|telecom
EchoStar|echostar.com,hughesnet.com|HughesNet|telecom
Viasat|viasat.com||telecom
US Cellular|uscellular.com||telecom
Cricket Wireless|cricketwireless.com||telecom
Mint Mobile|mintmobile.com||telecom
Google Fi|fi.google.com,google.com||telecom
Visible|visible.com||telecom
Straight Talk|straighttalk.com||telecom
Consumer Cellular|consumercellular.com||telecom
Xfinity Mobile|xfinity.com||telecom
Rogers Communications|rogers.com||telecom
Bell Canada|bell.ca||telecom
Telus|telus.com||telecom
Shaw Communications|shaw.ca||telecom
Videotron|videotron.com||telecom
BT Group|bt.com,btinternet.com,ee.co.uk|EE|telecom
Vodafone Group|vodafone.co.uk,vodafone.com,vodafone.de|Vodafone|telecom
Virgin Media O2|o2.co.uk,virginmedia.com|Virgin Media,O2|telecom
Sky Group|sky.com||telecom
TalkTalk|talktalk.co.uk,talktalk.net||telecom
Three UK|three.co.uk||telecom
Orange S.A.|orange.com,orange.fr|Orange|telecom
Deutsche Telekom|t-online.de,telekom.com,telekom.de|Telekom|telecom
Telefonica|movistar.com,movistar.es,telefonica.com|Movistar|telecom
Telecom Italia|gruppotim.it,tim.it|TIM|telecom
Swisscom|swisscom.ch,swisscom.com||telecom
KPN|kpn.com||telecom
Proximus|proximus.be||telecom
Telenor|telenor.com,telenor.no||telecom
Telia|telia.se,teliacompany.com||telecom
NTT|ntt.co.jp,ntt.com,nttdocomo.co.jp|NTT Docomo|telecom
SoftBank|softbank.co.jp,softbank.jp||telecom
KDDI|au.com,kddi.com|au|telecom
China Mobile|10086.cn,chinamobile.com||telecom
Telstra|telstra.com,telstra.com.au||telecom
Optus|optus.com.au||telecom
Spark New Zealand|spark.co.nz||telecom
Reliance Jio|jio.com||telecom
Bharti Airtel|airtel.com,airtel.in|Airtel|telecom
Vodafone Idea|myvi.in|Vi|telecom
Singtel|singtel.com||telecom
America Movil|americamovil.com,claro.com,telcel.com|Telcel,Claro|telecom
MTN Group|mtn.com||telecom
Etisalat|eand.com,etisalat.ae|e&|telecom
STC|stc.com.sa||telecom
U.S. Bancorp|usbank.com|US Bank|bank
PNC Financial Services|pnc.com|PNC|bank
Truist Financial|truist.com|Truist|bank
TD Bank Group|td.com,tdbank.com|TD|bank
Charles Schwab Corporation|schwab.com|Schwab|finance
Fidelity Investments|fidelity.com,fmr.com|Fidelity|finance
Vanguard Group|vanguard.com|Vanguard|finance
BlackRock|blackrock.com,ishares.com|iShares|finance
State Street|statestreet.com||finance
BNY Mellon|bny.com,bnymellon.com,pershing.com|Bank of New York Mellon|bank
Northern Trust|northerntrust.com||bank
Ally Financial|ally.com||bank
Discover Financial Services|discover.com||bank
Synchrony Financial|mysynchrony.com,syf.com,synchrony.com,synchronybank.com|Synchrony|bank
Citizens Financial Group|citizensbank.com||bank
Fifth Third Bancorp|53.com|Fifth Third|bank
KeyCorp|key.com|KeyBank|bank
Regions Financial|regions.com||bank
M&T Bank|mtb.com||bank
Huntington Bancshares|huntington.com|Huntington|bank
First Citizens BancShares|firstcitizens.com||bank
Comerica|comerica.com||bank
Zions Bancorporation|zionsbancorporation.com,zionsbank.com||bank
Santander US|santanderbank.com,santanderconsumerusa.com||bank
BMO Financial Group|bmo.com,bmoharris.com|BMO Harris|bank
Webster Bank|websterbank.com||bank
Western Alliance Bancorporation|westernalliancebancorporation.com||bank
East West Bank|eastwestbank.com||bank
Popular Inc|popular.com|Banco Popular|bank
Frost Bank|frostbank.com||bank
Valley National Bank|valley.com||bank
Synovus|synovus.com||bank
BOK Financial|bankofoklahoma.com,bokf.com|Bank of Oklahoma|bank
Associated Bank|associatedbank.com||bank
Old National Bank|oldnational.com||bank
Wintrust|wintrust.com||bank
Pinnacle Financial|pnfp.com||bank
First Horizon|firsthorizon.com||bank
Cullen/Frost|frostbank.com||bank
Flagstar Bank|flagstar.com||bank
New York Community Bancorp|mynycb.com||bank
Arvest Bank|arvest.com||bank
First National Bank of Omaha|fnbo.com||bank
UMB Financial|umb.com||bank
Commerce Bancshares|commercebank.com||bank
Bank OZK|ozk.com||bank
Navy Federal Credit Union|navyfederal.org||bank
PenFed Credit Union|penfed.org|PenFed|bank
State Employees' Credit Union|ncsecu.org|SECU|bank
SchoolsFirst Federal Credit Union|schoolsfirstfcu.org||bank
Golden 1 Credit Union|golden1.com||bank
Alliant Credit Union|alliantcreditunion.org||bank
BECU|becu.org||bank
America First Credit Union|americafirst.com||bank
Mountain America Credit Union|macu.com||bank
Suncoast Credit Union|suncoastcreditunion.com||bank
Marcus|marcus.com||bank
Capital One 360|capitalone.com||bank
Chime Financial|chime.com||finance
Varo Bank|varomoney.com||bank
Current|current.com||finance
Dave|dave.com||finance
MoneyLion|moneylion.com||finance
Upstart|upstart.com||finance
LendingClub|lendingclub.com||finance
Prosper|prosper.com||finance
Rocket Mortgage|quickenloans.com,rocketmortgage.com|Quicken Loans,Rocket Companies|finance
loanDepot|loandepot.com||finance
United Wholesale Mortgage|uwm.com|UWM|finance
Mr. Cooper|mrcooper.com||finance
PennyMac|pennymac.com||finance
Freedom Mortgage|freedommortgage.com||finance
Guild Mortgage|guildmortgage.com||finance
NewRez|newrez.com||finance
Caliber Home Loans|caliberhomeloans.com||finance
Navient|navient.com||finance
Nelnet|nelnet.com||finance
Sallie Mae|salliemae.com||finance
Mohela|mohela.com||finance
Great Lakes|mygreatlakes.org||finance
Aidvantage|aidvantage.com||finance
Edfinancial|edfinancial.com||finance
Federal Student Aid|studentaid.gov||government
Mastercard|mastercard.com,mastercard.us||finance
Visa Inc|visa.com|Visa|finance
Fiserv|clover.com,fiserv.com|Clover|finance
FIS|fisglobal.com,worldpay.com|Worldpay|finance
Global Payments|globalpayments.com,tsys.com|TSYS|finance
Block Inc|block.xyz,cash.app,squareup.com|Square,Cash App|finance
Adyen|adyen.com||finance
Checkout.com|checkout.com||finance
Payoneer|payoneer.com||finance
Wise plc|wise.com||finance
Remitly|remitly.com||finance
Xoom|xoom.com||finance
WorldRemit|worldremit.com||finance
Ria Money Transfer|riamoneytransfer.com||finance
Green Dot|greendot.com||finance
NetSpend|netspend.com||finance
Bill.com|bill.com||finance
Brex|brex.com||finance
Ramp|ramp.com||finance
Expensify|expensify.com||finance
Plaid|plaid.com||finance
Affirm Holdings|affirm.com||finance
Afterpay|afterpay.com||finance
Sezzle|sezzle.com||finance
Zip Co|zip.co||finance
Robinhood Markets|robinhood.com||finance
Webull|webull.com||finance
Interactive Brokers|ibkr.com,interactivebrokers.com|IBKR|finance
TD Ameritrade|tdameritrade.com||finance
Merrill Lynch|merrill.com,ml.com|Merrill|finance
Edward Jones|edwardjones.com||finance
Raymond James|raymondjames.com||finance
Ameriprise Financial|ameriprise.com||finance
LPL Financial|lpl.com||finance
Stifel|stifel.com||finance
Baird|rwbaird.com||finance
Wealthfront|wealthfront.com||finance
Betterment|betterment.com||finance
Acorns|acorns.com||finance
Stash|stash.com||finance
Public.com|public.com||finance
SoFi Technologies|sofi.com|SoFi|finance
Empower|empower-retirement.com,empower.com|Empower Retirement|finance
Principal Financial|principal.com||finance
TIAA|tiaa.org||finance
Voya Financial|voya.com||finance
Lincoln Financial|lfg.com,lincolnfinancial.com||insurance
Transamerica|transamerica.com||insurance
John Hancock|johnhancock.com||insurance
Guardian Life|guardianlife.com||insurance
MassMutual|massmutual.com||insurance
New York Life|newyorklife.com||insurance
Northwestern Mutual|northwesternmutual.com||insurance
Pacific Life|pacificlife.com||insurance
Brighthouse Financial|brighthousefinancial.com||insurance
Equitable|equitable.com||insurance
Unum|unum.com||insurance
Aflac|aflac.com||insurance
Globe Life|globelifeinsurance.com||insurance
Mutual of Omaha|mutualofomaha.com||insurance
Colonial Penn|colonialpenn.com||insurance
Primerica|primerica.com||insurance
AIG|aig.com|American International Group|insurance
Chubb|chubb.com||insurance
Hartford Financial|thehartford.com|The Hartford|insurance
Farmers Insurance|farmers.com||insurance
American Family Insurance|amfam.com||insurance
Erie Insurance|erieinsurance.com||insurance
Auto-Owners Insurance|auto-owners.com||insurance
Amica|amica.com||insurance
The General|thegeneral.com||insurance
Mercury Insurance|mercuryinsurance.com||insurance
Kemper|kemper.com||insurance
Root Insurance|joinroot.com||insurance
Lemonade|lemonade.com||insurance
Hippo|hippo.com||insurance
Assurant|assurant.com||insurance
Markel|markel.com||insurance
CNA Financial|cna.com||insurance
Zurich Insurance|zurich.com,zurichna.com|Zurich|insurance
Allianz|allianz.com,allianzlife.com||insurance
AXA|axa.co.uk,axa.com,axa.fr||insurance
Generali|generali.com||insurance
Munich Re|munichre.com||insurance
Swiss Re|swissre.com||insurance
Aviva|aviva.co.uk,aviva.com||insurance
Legal & General|legalandgeneral.com||insurance
Prudential plc|prudentialplc.com||insurance
Admiral Group|admiral.com||insurance
Direct Line|directline.com||insurance
Manulife|manulife.ca,manulife.com||insurance
Sun Life|sunlife.ca,sunlife.com||insurance
Great-West Lifeco|canadalife.com,greatwestlifeco.com|Canada Life|insurance
Intact Financial|intact.ca,intactfc.com||insurance
Desjardins|desjardins.com||bank
National Bank of Canada|nbc.ca||bank
Tangerine|tangerine.ca||bank
Simplii Financial|simplii.com||bank
EQ Bank|eqbank.ca||bank
ATB Financial|atb.com||bank
Laurentian Bank|laurentianbank.ca||bank
Lloyds Banking Group|bankofscotland.co.uk,halifax.co.uk,lloydsbank.com,lloydsbankinggroup.com|Bank of Scotland|bank
NatWest Group|natwest.com,natwestgroup.com,rbs.co.uk,ulsterbank.co.uk|Ulster Bank|bank
Barclays plc|barclaycard.co.uk,barclays.co.uk,barclays.com||bank
HSBC Holdings|firstdirect.com,hsbc.co.uk,hsbc.com,us.hsbc.com|First Direct|bank
Standard Chartered|sc.com||bank
Nationwide Building Society|nationwide.co.uk||bank
Virgin Money|virginmoney.com||bank
TSB Bank|tsb.co.uk||bank
Metro Bank|metrobankonline.co.uk||bank
Co-operative Bank|co-operativebank.co.uk||bank
Santander UK|santander.co.uk||bank
Tesco Bank|tescobank.com||bank
Sainsbury's Bank|sainsburysbank.co.uk||bank
Atom Bank|atombank.co.uk||bank
Chase UK|chase.co.uk||bank
Zopa|zopa.com||bank
Bank of Ireland|bankofireland.com||bank
AIB|aib.ie|Allied Irish Banks|bank
Permanent TSB|permanenttsb.ie||bank
BNP Paribas|bnpparibas.com,bnpparibas.fr||bank
Credit Agricole|credit-agricole.com,credit-agricole.fr||bank
Societe Generale|societegenerale.com,societegenerale.fr||bank
Groupe BPCE|banquepopulaire.fr,bpce.fr,caisse-epargne.fr|Banque Populaire,Caisse d'Epargne|bank
Credit Mutuel|creditmutuel.com,creditmutuel.fr||bank
La Banque Postale|labanquepostale.fr||bank
Boursorama|boursobank.com,boursorama.com|BoursoBank|bank
Commerzbank|commerzbank.com,commerzbank.de||bank
DZ Bank|dzbank.de||bank
Sparkasse|sparkasse.de||bank
Volksbank|vr.de||bank
Postbank|postbank.de||bank
UniCredit|hypovereinsbank.de,unicredit.it,unicreditgroup.eu|HypoVereinsbank|bank
Intesa Sanpaolo|intesasanpaolo.com||bank
Banco Santander|bancosantander.es,santander.com||bank
BBVA|bbva.com,bbva.es||bank
CaixaBank|caixabank.com,caixabank.es||bank
Banco Sabadell|bancsabadell.com||bank
Bankinter|bankinter.com||bank
ABN AMRO|abnamro.com,abnamro.nl||bank
Rabobank|rabobank.com,rabobank.nl||bank
Bunq|bunq.com||bank
KBC|kbc.be,kbc.com||bank
Belfius|belfius.be||bank
UBS|ubs.com||bank
Credit Suisse|credit-suisse.com||bank
Raiffeisen|raiffeisen.at,raiffeisen.ch,rbinternational.com||bank
Erste Group|erstebank.at,erstegroup.com,sparkasse.at||bank
Nordea|nordea.com,nordea.dk,nordea.fi,nordea.se||bank
Danske Bank|danskebank.com,danskebank.dk||bank
DNB|dnb.no||bank
SEB|seb.se,sebgroup.com||bank
Swedbank|swedbank.com,swedbank.se||bank
Handelsbanken|handelsbanken.com,handelsbanken.se||bank
OP Financial Group|op.fi||bank
PKO Bank Polski|pkobp.pl||bank
mBank|mbank.pl||bank
Sberbank|sber.ru,sberbank.ru|Sber|bank
Tinkoff|tbank.ru,tinkoff.ru|T-Bank|bank
Mitsubishi UFJ Financial|bk.mufg.jp,mufg.jp|MUFG|bank
Sumitomo Mitsui Financial|smbc.co.jp,smfg.co.jp|SMBC|bank
Mizuho Financial|mizuho-fg.com,mizuhobank.co.jp|Mizuho|bank
Japan Post Bank|jp-bank.japanpost.jp||bank
Nomura|nomura.co.jp,nomura.com||finance
Rakuten Bank|rakuten-bank.co.jp||bank
Industrial and Commercial Bank of China|icbc.com.cn|ICBC|bank
China Construction Bank|ccb.com||bank
Agricultural Bank of China|abchina.com||bank
Bank of China|bankofchina.com,boc.cn||bank
Bank of Communications|bankcomm.com||bank
China Merchants Bank|cmbchina.com||bank
Ping An|pingan.cn,pingan.com||insurance
China Life|chinalife.com.cn,e-chinalife.com||insurance
AIA Group|aia.com||insurance
HSBC Hong Kong|hsbc.com.hk||bank
Hang Seng Bank|hangseng.com||bank
Bank of East Asia|hkbea.com||bank
DBS Bank|dbs.com,dbs.com.sg|DBS|bank
OCBC Bank|ocbc.com|OCBC|bank
UOB|uob.com.sg,uobgroup.com|United Overseas Bank|bank
Maybank|maybank.com,maybank2u.com.my||bank
CIMB|cimb.com||bank
Public Bank Berhad|pbebank.com||bank
Bangkok Bank|bangkokbank.com||bank
Kasikornbank|kasikornbank.com|KBank|bank
Siam Commercial Bank|scb.co.th|SCB|bank
Bank Central Asia|bca.co.id|BCA|bank
Bank Mandiri|bankmandiri.co.id||bank
Bank Rakyat Indonesia|bri.co.id|BRI|bank
BDO Unibank|bdo.com.ph|BDO|bank
BPI|bpi.com.ph|Bank of the Philippine Islands|bank
Metrobank|metrobank.com.ph||bank
State Bank of India|onlinesbi.sbi,sbi.co.in|SBI|bank
HDFC Bank|hdfcbank.com||bank
ICICI Bank|icicibank.com||bank
Axis Bank|axisbank.com||bank
Kotak Mahindra Bank|kotak.com||bank
Punjab National Bank|pnbindia.in|PNB|bank
Bank of Baroda|bankofbaroda.in||bank
Yes Bank|yesbank.in||bank
IndusInd Bank|indusind.com||bank
Paytm|paytm.com||finance
PhonePe|phonepe.com||finance
Razorpay|razorpay.com||finance
Commonwealth Bank of Australia|commbank.com.au|CommBank|bank
Westpac Banking|westpac.com.au|Westpac|bank
ANZ Group|anz.co.nz,anz.com,anz.com.au|ANZ|bank
National Australia Bank|nab.com.au|NAB|bank
Macquarie Group|macquarie.com,macquarie.com.au|Macquarie|bank
Bendigo Bank|bendigobank.com.au||bank
Bank of Queensland|boq.com.au|BOQ|bank
Suncorp|suncorp.com.au||insurance
ING Australia|ing.com.au||bank
ASB Bank|asb.co.nz|ASB|bank
Kiwibank|kiwibank.co.nz||bank
BNZ|bnz.co.nz|Bank of New Zealand|bank
Standard Bank|standardbank.co.za,standardbank.com||bank
FirstRand|firstrand.co.za,fnb.co.za|FNB,First National Bank|bank
Absa|absa.africa,absa.co.za||bank
Nedbank|nedbank.co.za||bank
Capitec|capitecbank.co.za||bank
Investec|investec.com||bank
Discovery Limited|discovery.co.za||insurance
Sanlam|sanlam.co.za||insurance
Old Mutual|oldmutual.co.za,oldmutual.com||insurance
Emirates NBD|emiratesnbd.com||bank
First Abu Dhabi Bank|bankfab.com|FAB|bank
Abu Dhabi Commercial Bank|adcb.com|ADCB|bank
Dubai Islamic Bank|dib.ae||bank
Mashreq|mashreq.com,mashreqbank.com||bank
Qatar National Bank|qnb.com|QNB|bank
Al Rajhi Bank|alrajhibank.com.sa||bank
Saudi National Bank|alahli.com|SNB|bank
Riyad Bank|riyadbank.com||bank
National Bank of Kuwait|nbk.com|NBK|bank
Bank Leumi|leumi.co.il||bank
Bank Hapoalim|bankhapoalim.co.il||bank
Itau Unibanco|itau.com.br|Itau|bank
Banco do Brasil|bb.com.br||bank
Bradesco|bradesco.com.br||bank
Caixa Economica Federal|caixa.gov.br|Caixa|bank
Nubank|nu.com,nu.com.br,nubank.com.br|Nu|bank
Banco Inter|bancointer.com.br||bank
BTG Pactual|btgpactual.com||bank
XP Inc|xpi.com.br||finance
Mercado Pago|mercadopago.com,mercadopago.com.ar,mercadopago.com.br||finance
Banorte|banorte.com||bank
BBVA Mexico|bbva.mx||bank
Citibanamex|banamex.com||bank
Banco Azteca|bancoazteca.com.mx||bank
Bancolombia|bancolombia.com||bank
Banco de Chile|bancochile.cl||bank
Banco Galicia|bancogalicia.com||bank
Banco de Credito del Peru|viabcp.com|BCP|bank
Klarna Bank|klarna.com||finance
Revolut Ltd|revolut.com||bank
N26 Bank|n26.com||bank
Monzo Bank|monzo.com||bank
Starling Bank Ltd|starlingbank.com||bank
Qonto|qonto.com||bank
Lydia|lydia-app.com||finance
Trade Republic|traderepublic.com||finance
eToro|etoro.com||finance
IG Group|ig.com||finance
Plus500|plus500.com||finance
Saxo Bank|home.saxo||finance
Hargreaves Lansdown|hl.co.uk||finance
AJ Bell|ajbell.co.uk||finance
Moneybox|moneyboxapp.com||finance
Nutmeg|nutmeg.com||finance
Interactive Investor|ii.co.uk||finance
Bitcoin.com|bitcoin.com||finance
Crypto.com|crypto.com||finance
Gemini|gemini.com||finance
Bitstamp|bitstamp.net||finance
Bitfinex|bitfinex.com||finance
KuCoin|kucoin.com||finance
OKX|okx.com||finance
Bybit|bybit.com||finance
Ledger|ledger.com||finance
Trezor|trezor.io||finance
MetaMask|metamask.io||finance
Uphold|uphold.com||finance
BlockFi|blockfi.com||finance
Equifax Inc|equifax.com||finance
Experian plc|experian.co.uk,experian.com||finance
TransUnion LLC|transunion.com||finance
FICO|fico.com,myfico.com||finance
Credit Karma|creditkarma.com||finance
NerdWallet|nerdwallet.com||finance
Bankrate|bankrate.com||finance
LendingTree|lendingtree.com||finance
Intuit Inc|intuit.com,quickbooks.intuit.com,turbotax.intuit.com|TurboTax,QuickBooks|finance
H&R Block|hrblock.com||finance
Jackson Hewitt|jacksonhewitt.com||finance
TaxAct|taxact.com||finance
Paychex|paychex.com||finance
ADP LLC|adp.com|ADP|finance
Paycom|paycom.com||finance
Paylocity|paylocity.com||finance
Gusto|gusto.com||finance
Workday|workday.com||tech
Ceridian|ceridian.com,dayforce.com|Dayforce|finance
Aon|aon.com||insurance
Marsh McLennan|marsh.com,mercer.com,mmc.com|Marsh,Mercer|insurance
Willis Towers Watson|wtwco.com|WTW|insurance
Arthur J. Gallagher|ajg.com|Gallagher|insurance
Brown & Brown|bbinsurance.com||insurance
Berkshire Hathaway GEICO|geico.com||insurance
Berkshire Hathaway HomeServices|bhhs.com||realestate
Moody's|moodys.com||finance
S&P Global|spglobal.com||finance
MSCI|msci.com||finance
Nasdaq|nasdaq.com||finance
Intercontinental Exchange|ice.com,nyse.com|NYSE|finance
CME Group|cmegroup.com||finance
Cboe|cboe.com||finance
London Stock Exchange Group|lseg.com|LSEG|finance
Deutsche Boerse|deutsche-boerse.com||finance
Euronext|euronext.com||finance
Broadridge|broadridge.com||finance
Computershare|computershare.com||finance
Equiniti|equiniti.com||finance
DTCC|dtcc.com||finance
SWIFT|swift.com||finance
Western Union Company|westernunion.com||finance
MoneyGram International|moneygram.com||finance
Euronet|euronetworldwide.com||finance
Cardtronics|cardtronics.com||finance
NCR Voyix|ncr.com,ncrvoyix.com|NCR|tech
Diebold Nixdorf|dieboldnixdorf.com||tech
Jack Henry|jackhenry.com||tech
Q2|q2.com||tech
nCino|ncino.com||tech
Temenos|temenos.com||tech
Finastra|finastra.com||tech
Guidewire|guidewire.com||tech
Duck Creek|duckcreek.com||tech
Verisk|verisk.com||finance
CoreLogic|corelogic.com||finance
Black Knight|blackknightinc.com||finance
First American Financial|firstam.com||insurance
Fidelity National Financial|fnf.com||insurance
Stewart Title|stewart.com||insurance
Old Republic|oldrepublic.com||insurance
Radian|radian.com||insurance
MGIC|mgic.com||insurance
Genworth|genworth.com||insurance
Enact|enactmi.com||insurance
Assured Guaranty|assuredguaranty.com||insurance
Ambac|ambac.com||insurance
Hewlett Packard Enterprise|hpe.com|HPE|tech
HP Inc|hp.com|Hewlett-Packard|tech
Lenovo Group|lenovo.com,motorola.com|Lenovo,Motorola|tech
Samsung Electronics|samsung.com,samsungusa.com|Samsung|tech
LG Electronics|lg.com,lge.com|LG|tech
Sony Group|playstation.com,sony.co.jp,sony.com,sony.net|Sony,PlayStation|tech
Panasonic|panasonic.com,panasonic.jp,panasonic.net||tech
Toshiba|global.toshiba,toshiba.co.jp,toshiba.com||tech
Hitachi|hitachi.co.jp,hitachi.com||industrial
Fujitsu|fujitsu.com||tech
NEC Corporation|nec.com|NEC|tech
Sharp Corporation|global.sharp,sharp.co.jp,sharpusa.com|Sharp|tech
Canon|canon.co.jp,canon.co.uk,canon.com,usa.canon.com||tech
Nikon|nikon.com,nikonusa.com||tech
Epson|epson.co.jp,epson.co.uk,epson.com||tech
Brother Industries|brother-usa.com,brother.co.uk,brother.com|Brother|tech
Ricoh|ricoh-usa.com,ricoh.com||tech
Kyocera|kyocera.com,kyoceradocumentsolutions.com||tech
Xerox|xerox.com||tech
Lexmark|lexmark.com||tech
Logitech|logi.com,logitech.com||tech
Corsair|corsair.com||tech
Razer|razer.com||tech
Asus|asus.com||tech
Acer|acer.com||tech
MSI|msi.com||tech
Gigabyte|gigabyte.com||tech
Foxconn|foxconn.com,honhai.com|Hon Hai|tech
Pegatron|pegatron.com||tech
TSMC|tsmc.com|Taiwan Semiconductor|tech
MediaTek|mediatek.com||tech
Texas Instruments|ti.com||tech
Analog Devices|analog.com||tech
Microchip Technology|microchip.com||tech
NXP Semiconductors|nxp.com||tech
Infineon|infineon.com||tech
STMicroelectronics|st.com||tech
ON Semiconductor|onsemi.com||tech
Marvell|marvell.com||tech
Skyworks|skyworksinc.com||tech
Qorvo|qorvo.com||tech
Lam Research|lamresearch.com||tech
KLA Corporation|kla.com|KLA|tech
ASML|asml.com||tech
Arm Holdings|arm.com|Arm|tech
SK Hynix|skhynix.com||tech
Western Digital|wdc.com,westerndigital.com|WD,SanDisk|tech
Seagate|seagate.com||tech
Kingston Technology|kingston.com||tech
Super Micro Computer|supermicro.com|Supermicro|tech
NetApp|netapp.com||tech
Pure Storage|purestorage.com||tech
Nutanix|nutanix.com||tech
Juniper Networks|juniper.net||tech
Arista Networks|arista.com||tech
Extreme Networks|extremenetworks.com||tech
Ubiquiti|ubnt.com,ui.com||tech
Netgear|netgear.com||tech
TP-Link|tp-link.com||tech
D-Link|dlink.com||tech
Belkin|belkin.com,linksys.com|Linksys|tech
Eero|eero.com||tech
Garmin|garmin.com||tech
Fitbit|fitbit.com||tech
GoPro|gopro.com||tech
Sonos|sonos.com||tech
Bose|bose.com||tech
JBL|harman.com,jbl.com|Harman|tech
Sennheiser|sennheiser.com||tech
Beats|beatsbydre.com||tech
Roku Inc|roku.com|Roku|media
Vizio|vizio.com||tech
TCL|tcl.com||tech
Hisense|hisense-usa.com,hisense.com||tech
Xiaomi|mi.com,xiaomi.com||tech
Huawei|huawei.com||tech
Oppo|oppo.com||tech
Vivo|vivo.com||tech
OnePlus|oneplus.com||tech
Realme|realme.com||tech
Honor|hihonor.com||tech
ZTE|zte.com.cn||tech
Nokia|nokia.com||tech
Ericsson|ericsson.com||tech
Motorola Solutions|motorolasolutions.com||tech
BlackBerry|blackberry.com||tech
Palantir|palantir.com||tech
Snowflake|snowflake.com||tech
Databricks|databricks.com||tech
MongoDB|mongodb.com||tech
Elastic|elastic.co||tech
Confluent|confluent.io||tech
HashiCorp|hashicorp.com||tech
Datadog|datadoghq.com||tech
Splunk|splunk.com||tech
New Relic|newrelic.com||tech
Dynatrace|dynatrace.com||tech
PagerDuty|pagerduty.com||tech
ServiceNow|servicenow.com||tech
Zendesk|zendesk.com||tech
Freshworks|freshworks.com||tech
HubSpot|hubspot.com||tech
Marketo|marketo.com||tech
Mailchimp|mailchimp.com||tech
Constant Contact|constantcontact.com||tech
Klaviyo|klaviyo.com||tech
Braze|braze.com||tech
Twilio Inc|sendgrid.com,twilio.com|Twilio,SendGrid|tech
Vonage|vonage.com||tech
RingCentral|ringcentral.com||tech
8x8|8x8.com||tech
Five9|five9.com||tech
Genesys|genesys.com||tech
NICE|nice.com||tech
Avaya|avaya.com||tech
Mitel|mitel.com||tech
Poly|poly.com||tech
Zoom Video Communications|zoom.com,zoom.us|Zoom|tech
Cisco Webex|webex.com||tech
GoTo|goto.com,logmein.com|LogMeIn,GoToMeeting|tech
TeamViewer|teamviewer.com||tech
AnyDesk|anydesk.com||tech
Splashtop|splashtop.com||tech
Citrix|citrix.com,cloud.com||tech
Okta|okta.com||tech
Auth0|auth0.com||tech
Ping Identity|pingidentity.com||tech
CyberArk|cyberark.com||tech
SailPoint|sailpoint.com||tech
Duo Security|duo.com||tech
Yubico|yubico.com||tech
CrowdStrike|crowdstrike.com||security
Palo Alto Networks|paloaltonetworks.com||security
Fortinet|fortinet.com||security
Check Point|checkpoint.com||security
Zscaler|zscaler.com||security
SentinelOne|sentinelone.com||security
Sophos|sophos.com||security
Trend Micro|trendmicro.com||security
ESET|eset.com||security
F-Secure|f-secure.com||security
Webroot|webroot.com||security
Proofpoint|proofpoint.com||security
Mimecast|mimecast.com||security
Barracuda Networks|barracuda.com||security
Rapid7|rapid7.com||security
Tenable|tenable.com||security
Qualys|qualys.com||security
Varonis|varonis.com||security
KnowBe4|knowbe4.com||security
Cloudflare Inc|cloudflare.com||tech
Akamai|akamai.com||tech
Fastly|fastly.com||tech
DigitalOcean|digitalocean.com||tech
Linode|linode.com||tech
Vultr|vultr.com||tech
Hetzner|hetzner.com,hetzner.de||tech
OVHcloud|ovh.com,ovhcloud.com|OVH|tech
Scaleway|scaleway.com||tech
Rackspace|rackspace.com||tech
Equinix|equinix.com||tech
Digital Realty|digitalrealty.com||tech
Oracle Cloud|oracle.com||tech
Google Cloud|cloud.google.com,google.com||tech
Amazon Web Services|amazonaws.com,aws.amazon.com|AWS|tech
Microsoft Azure|azure.com,azure.microsoft.com|Azure|tech
Alibaba Cloud|alibabacloud.com,aliyun.com|Aliyun|tech
Tencent|qq.com,tencent.com,weixin.qq.com|WeChat,QQ|tech
Baidu|baidu.com||tech
ByteDance|bytedance.com,tiktok.com|TikTok|tech
JD.com|jd.com||retail
Pinduoduo|pinduoduo.com,temu.com|Temu|retail
Meituan|meituan.com||tech
Didi|didiglobal.com||tech
NetEase|163.com,netease.com||tech
Sina|sina.com.cn,weibo.com|Weibo|tech
Kuaishou|kuaishou.com||tech
Bilibili|bilibili.com||tech
Naver Corporation|naver.com|Naver|tech
Kakao|kakao.com,kakaocorp.com|KakaoTalk|tech
Line Corporation|line.me,linecorp.com|LINE|tech
Yahoo Japan|yahoo.co.jp||tech
Mercari Inc|mercari.com,mercari.jp||retail
Rakuten Group|rakuten.co.jp,rakuten.com|Rakuten|retail
Grab|grab.com||tech
Gojek|gojek.com,gotocompany.com|GoTo|tech
Sea Limited|garena.com,sea.com,shopee.com|Shopee,Garena|tech
Lazada|lazada.com,lazada.sg||retail
Tokopedia|tokopedia.com||retail
Flipkart|flipkart.com||retail
Myntra|myntra.com||retail
Swiggy|swiggy.com||tech
Zomato|zomato.com||tech
Ola|olacabs.com||tech
Infosys|infosys.com||tech
Tata Consultancy Services|tcs.com|TCS|tech
Wipro|wipro.com||tech
HCLTech|hcltech.com|HCL Technologies|tech
Tech Mahindra|techmahindra.com||tech
Cognizant|cognizant.com||tech
Accenture|accenture.com||tech
Capgemini|capgemini.com||tech
Atos|atos.net||tech
DXC Technology|dxc.com|DXC|tech
Kyndryl|kyndryl.com||tech
Leidos|leidos.com||tech
SAIC|saic.com||tech
Booz Allen Hamilton|boozallen.com||tech
CACI|caci.com||tech
ManTech|mantech.com||tech
Deloitte|deloitte.com||consulting
PwC|pwc.com|PricewaterhouseCoopers|consulting
EY|ey.com|Ernst & Young|consulting
KPMG|kpmg.com,kpmg.us||consulting
McKinsey & Company|mckinsey.com|McKinsey|consulting
Boston Consulting Group|bcg.com|BCG|consulting
Bain & Company|bain.com|Bain|consulting
Gartner|gartner.com||consulting
Forrester|forrester.com||consulting
IDC|idc.com||consulting
Nielsen|nielsen.com||consulting
SAP SE|ariba.com,concur.com,sap.com,successfactors.com|SAP,Concur,SuccessFactors,Ariba|tech
Siemens|siemens-healthineers.com,siemens.com|Siemens Healthineers|industrial
Software AG|softwareag.com||tech
TeamViewer AG|teamviewer.com||tech
Dassault Systemes|3ds.com|Dassault|tech
Autodesk|autodesk.com||tech
PTC|ptc.com||tech
Ansys|ansys.com||tech
Synopsys|synopsys.com||tech
Cadence Design Systems|cadence.com||tech
Bentley Systems|bentley.com||tech
Trimble|trimble.com||tech
Esri|esri.com||tech
Unity Technologies|unity.com||tech
Roblox|roblox.com||tech
Electronic Arts|ea.com|EA|tech
Activision Blizzard|activision.com,battle.net,blizzard.com|Blizzard,Battle.net|tech
Take-Two Interactive|2k.com,rockstargames.com,take2games.com|Rockstar Games,2K|tech
Ubisoft|ubi.com,ubisoft.com||tech
Square Enix|square-enix-games.com,square-enix.com||tech
Bandai Namco|bandainamco.com,bandainamcoent.com||tech
Capcom|capcom.com||tech
Sega|sega.com,sega.jp||tech
Konami|konami.com||tech
Nexon|nexon.com||tech
NCSoft|ncsoft.com||tech
Krafton|krafton.com||tech
miHoYo|hoyoverse.com,mihoyo.com|HoYoverse|tech
Supercell|supercell.com||tech
King|king.com||tech
Zynga|zynga.com||tech
Playtika|playtika.com||tech
Scopely|scopely.com||tech
Niantic|nianticlabs.com||tech
GOG|gog.com||tech
Humble Bundle|humblebundle.com||tech
itch.io|itch.io||tech
Discord Inc|discord.com||tech
Twitch Interactive|twitch.tv||media
Reddit Inc|reddit.com,redditmail.com|Reddit|tech
Pinterest Inc|pinterest.com|Pinterest|tech
Snap Inc|snap.com,snapchat.com|Snapchat|tech
Tumblr|tumblr.com||tech
Quora|quora.com||tech
Medium|medium.com||tech
Substack Inc|substack.com|Substack|tech
Patreon|patreon.com||tech
Kickstarter|kickstarter.com||tech
Indiegogo|indiegogo.com||tech
GoFundMe|gofundme.com||tech
Eventbrite|eventbrite.com||tech
Meetup|meetup.com||tech
Yelp|yelp.com||tech
Angi|angi.com,angieslist.com,homeadvisor.com|Angie's List,HomeAdvisor|tech
Thumbtack|thumbtack.com||tech
TaskRabbit|taskrabbit.com||tech
Fiverr|fiverr.com||tech
Upwork|upwork.com||tech
Freelancer|freelancer.com||tech
Toptal|toptal.com||tech
Wix.com|wix.com||tech
Squarespace Inc|squarespace.com||tech
GoDaddy Inc|godaddy.com,secureserver.net||tech
Namecheap Inc|namecheap.com||tech
Automattic|automattic.com,tumblr.com,woocommerce.com,wordpress.com|WooCommerce|tech
Bluehost|bluehost.com||tech
HostGator|hostgator.com||tech
SiteGround|siteground.com||tech
DreamHost|dreamhost.com||tech
WP Engine|wpengine.com||tech
Kinsta|kinsta.com||tech
Cloudways|cloudways.com||tech
Hostinger International|hostinger.com||tech
IONOS SE|ionos.co.uk,ionos.com,ionos.de|1&1 IONOS|tech
Strato|strato.de||tech
Gandi|gandi.net||tech
Network Solutions|networksolutions.com||tech
Register.com|register.com||tech
Tucows|hover.com,tucows.com|Hover|tech
Dynadot|dynadot.com||tech
Porkbun|porkbun.com||tech
Google Domains|domains.google||tech
Vercel|vercel.com||tech
Netlify|netlify.com||tech
Heroku|heroku.com||tech
Render|render.com||tech
Fly.io|fly.io||tech
Railway|railway.app||tech
Supabase|supabase.com||tech
Firebase|firebase.google.com||tech
Twilio Segment|segment.com||tech
Amplitude|amplitude.com||tech
Mixpanel|mixpanel.com||tech
Hotjar|hotjar.com||tech
Optimizely|optimizely.com||tech
Contentful|contentful.com||tech
Sanity|sanity.io||tech
Algolia|algolia.com||tech
Auth0 Inc|auth0.com||tech
Postman|postman.com||tech
JetBrains|jetbrains.com||tech
GitLab|gitlab.com||tech
Bitbucket|bitbucket.org||tech
Docker Inc|docker.com,docker.io|Docker|tech
Red Hat Inc|redhat.com||tech
SUSE|suse.com||tech
Canonical|canonical.com,ubuntu.com|Ubuntu|tech
Linux Foundation|linuxfoundation.org||tech
Apache Software Foundation|apache.org||tech
Python Software Foundation|python.org||tech
npm|npmjs.com||tech
PyPI|pypi.org||tech
Stack Overflow|stackexchange.com,stackoverflow.com||tech
Wikipedia|wikimedia.org,wikipedia.org|Wikimedia Foundation|tech
Internet Archive|archive.org||tech
Mozilla Foundation|mozilla.com,mozilla.org||tech
Brave Software|brave.com||tech
DuckDuckGo|duckduckgo.com||tech
Opera|opera.com||tech
Vivaldi|vivaldi.com||tech
Kagi|kagi.com||tech
Bing|bing.com||tech
Evernote Corporation|evernote.com||tech
Notion Labs|notion.so||tech
Asana|asana.com||tech
Trello|trello.com||tech
Monday.com|monday.com||tech
ClickUp|clickup.com||tech
Smartsheet|smartsheet.com||tech
Airtable|airtable.com||tech
Basecamp|37signals.com,basecamp.com,hey.com|HEY|tech
Miro|miro.com||tech
Figma Inc|figma.com|Figma|tech
Canva Pty|canva.com|Canva|tech
Adobe Inc|adobe.com|Adobe|tech
Affinity|affinity.serif.com,serif.com|Serif|tech
CorelDRAW|corel.com,coreldraw.com|Corel|tech
Avid|avid.com||tech
Blackmagic Design|blackmagicdesign.com||tech
Steinberg|steinberg.net||tech
Native Instruments|native-instruments.com||tech
Ableton|ableton.com||tech
Dropbox Inc|dropbox.com|Dropbox|tech
Box Inc|box.com|Box|tech
Egnyte|egnyte.com||tech
Sync.com|sync.com||tech
pCloud|pcloud.com||tech
MEGA|mega.io,mega.nz||tech
Backblaze|backblaze.com||tech
Carbonite|carbonite.com||tech
IDrive|idrive.com||tech
Acronis|acronis.com||tech
Veeam|veeam.com||tech
Commvault|commvault.com||tech
Veritas|veritas.com||tech
Zoho Corporation|zoho.com||tech
Freshdesk|freshdesk.com||tech
Intercom|intercom.com||tech
Drift|drift.com||tech
LiveChat|livechat.com||tech
Tidio|tidio.com||tech
Calendly|calendly.com||tech
Doodle|doodle.com||tech
SurveyMonkey|surveymonkey.com||tech
Typeform|typeform.com||tech
Qualtrics|qualtrics.com||tech
Medallia|medallia.com||tech
Sprinklr|sprinklr.com||tech
Hootsuite|hootsuite.com||tech
Buffer|buffer.com||tech
Sprout Social|sproutsocial.com||tech
Semrush|semrush.com||tech
Ahrefs|ahrefs.com||tech
Moz|moz.com||tech
Similarweb|similarweb.com||tech
LastPass Inc|lastpass.com||security
Dashlane|dashlane.com||security
Bitwarden|bitwarden.com||security
NordVPN|nordaccount.com,nordvpn.com|Nord Security|security
ExpressVPN|expressvpn.com||security
Surfshark|surfshark.com||security
Proton AG|proton.me,protonmail.com,protonvpn.com|ProtonVPN|security
Mullvad|mullvad.net||security
Private Internet Access|privateinternetaccess.com||security
CyberGhost|cyberghostvpn.com||security
IPVanish|ipvanish.com||security
Avira|avira.com||security
AVG Technologies|avg.com||security
Malwarebytes Inc|malwarebytes.com||security
CCleaner|ccleaner.com||security
Grammarly Inc|grammarly.com||tech
Duolingo Inc|duolingo.com||education
Babbel|babbel.com||education
Rosetta Stone|rosettastone.com||education
Coursera Inc|coursera.org||education
edX|edx.org||education
Udacity|udacity.com||education
Udemy Inc|udemy.com||education
Skillshare|skillshare.com||education
MasterClass|masterclass.com||education
Pluralsight|pluralsight.com||education
LinkedIn Learning|linkedin.com||education
Khan Academy|khanacademy.org||education
Chegg|chegg.com||education
Quizlet|quizlet.com||education
Course Hero|coursehero.com||education
Brainly|brainly.com||education
Kahoot|kahoot.com,kahoot.it||education
Blackboard|blackboard.com||education
Canvas LMS|instructure.com|Instructure|education
Moodle|moodle.com,moodle.org||education
Pearson|pearson.com||education
McGraw Hill|mheducation.com||education
Cengage|cengage.com||education
Scholastic|scholastic.com||education
Wiley|wiley.com||education
Elsevier|elsevier.com||education
Springer Nature|springer.com,springernature.com||education
College Board|collegeboard.org||education
ACT|act.org||education
ETS|ets.org||education
Common App|commonapp.org||education
FAFSA|studentaid.gov||government
Handshake|joinhandshake.com||education
Roche|genentech.com,roche.com|Genentech|pharma
Novartis|novartis.com,sandoz.com|Sandoz|pharma
AstraZeneca|astrazeneca.com||pharma
GSK|gsk.com,haleon.com|GlaxoSmithKline,Haleon|pharma
Sanofi|sanofi.com,sanofi.us||pharma
Bayer|bayer.com,bayer.us||pharma
Novo Nordisk|novonordisk.com||pharma
Boehringer Ingelheim|boehringer-ingelheim.com||pharma
Takeda|takeda.com||pharma
Astellas|astellas.com||pharma
Daiichi Sankyo|daiichisankyo.com||pharma
Eisai|eisai.com||pharma
Otsuka|otsuka-us.com,otsuka.com||pharma
Gilead Sciences|gilead.com||pharma
Regeneron|regeneron.com||pharma
Vertex Pharmaceuticals|vrtx.com||pharma
Moderna|modernatx.com||pharma
BioNTech|biontech.com,biontech.de||pharma
Biogen|biogen.com||pharma
Zoetis|zoetis.com||pharma
Viatris|mylan.com,viatris.com|Mylan|pharma
Teva|tevapharm.com,tevausa.com||pharma
Sun Pharma|sunpharma.com||pharma
Dr. Reddy's|drreddys.com||pharma
Cipla|cipla.com||pharma
Lupin|lupin.com||pharma
Perrigo|perrigo.com||pharma
Bausch Health|bausch.com,bauschhealth.com|Bausch + Lomb|pharma
Organon|organon.com||pharma
Jazz Pharmaceuticals|jazzpharma.com||pharma
Alnylam|alnylam.com||pharma
Incyte|incyte.com||pharma
BeiGene|beigene.com||pharma
CSL|csl.com,cslbehring.com|CSL Behring|pharma
Grifols|grifols.com||pharma
Medtronic|medtronic.com||healthcare
Stryker|stryker.com||healthcare
Boston Scientific|bostonscientific.com||healthcare
Becton Dickinson|bd.com|BD|healthcare
Baxter|baxter.com||healthcare
Zimmer Biomet|zimmerbiomet.com||healthcare
Edwards Lifesciences|edwards.com||healthcare
Intuitive Surgical|intuitive.com||healthcare
Dexcom|dexcom.com||healthcare
Insulet|insulet.com,omnipod.com|Omnipod|healthcare
Tandem Diabetes|tandemdiabetes.com||healthcare
ResMed|resmed.com||healthcare
Philips|philips.com,usa.philips.com||healthcare
GE HealthCare|gehealthcare.com||healthcare
Fresenius|fresenius.com,freseniuskidneycare.com,freseniusmedicalcare.com|Fresenius Medical Care|healthcare
DaVita|davita.com||healthcare
Hologic|hologic.com||healthcare
Illumina|illumina.com||healthcare
Agilent|agilent.com||healthcare
Waters Corporation|waters.com||healthcare
PerkinElmer|perkinelmer.com,revvity.com|Revvity|healthcare
Bio-Rad|bio-rad.com||healthcare
Quest Diagnostics|questdiagnostics.com||healthcare
Labcorp|labcorp.com||healthcare
IQVIA|iqvia.com||healthcare
Charles River Laboratories|criver.com||healthcare
Catalent|catalent.com||healthcare
West Pharmaceutical|westpharma.com||healthcare
Cooper Companies|coopercos.com,coopervision.com|CooperVision|healthcare
Align Technology|aligntech.com,invisalign.com|Invisalign|healthcare
Dentsply Sirona|dentsplysirona.com||healthcare
Henry Schein|henryschein.com||healthcare
Patterson Companies|pattersoncompanies.com||healthcare
Owens & Minor|owens-minor.com||healthcare
Medline|medline.com||healthcare
Steris|steris.com||healthcare
Teleflex|teleflex.com||healthcare
Masimo|masimo.com||healthcare
Omron|omron.com,omronhealthcare.com||healthcare
Kaiser Foundation|kp.org||healthcare
Mayo Clinic|mayo.edu,mayoclinic.org||healthcare
Cleveland Clinic|ccf.org,clevelandclinic.org||healthcare
Johns Hopkins Medicine|hopkinsmedicine.org,jhu.edu||healthcare
Mass General Brigham|massgeneralbrigham.org,mgh.harvard.edu||healthcare
NewYork-Presbyterian|nyp.org||healthcare
Mount Sinai Health|mountsinai.org||healthcare
NYU Langone|nyulangone.org||healthcare
Cedars-Sinai|cedars-sinai.org||healthcare
UCLA Health|uclahealth.org||healthcare
UCSF Health|ucsfhealth.org||healthcare
Stanford Health Care|stanfordhealthcare.org||healthcare
Northwell Health|northwell.edu||healthcare
Ascension|ascension.org||healthcare
CommonSpirit Health|commonspirit.org||healthcare
Providence|providence.org||healthcare
Advocate Health|aah.org,advocatehealth.org||healthcare
Intermountain Health|intermountainhealthcare.org||healthcare
Banner Health|bannerhealth.com||healthcare
Sutter Health|sutterhealth.org||healthcare
Trinity Health|trinity-health.org||healthcare
Universal Health Services|uhs.com,uhsinc.com||healthcare
Community Health Systems|chs.net||healthcare
Encompass Health|encompasshealth.com||healthcare
Teladoc|teladochealth.com||healthcare
Hims & Hers|forhers.com,hims.com|Hims,Hers|healthcare
GoodRx|goodrx.com||healthcare
Zocdoc|zocdoc.com||healthcare
WebMD|webmd.com||healthcare
Healthline|healthline.com||healthcare
Epic Systems|epic.com,mychart.com|MyChart|healthcare
Oracle Health|cerner.com,oracle.com|Cerner|healthcare
athenahealth|athenahealth.com||healthcare
Veeva Systems|veeva.com||healthcare
Change Healthcare|changehealthcare.com||healthcare
Availity|availity.com||healthcare
Blue Cross Blue Shield Association|bcbs.com||insurance
Blue Shield of California|blueshieldca.com||insurance
Anthem Blue Cross|anthem.com||insurance
Health Care Service Corporation|bcbsil.com,bcbstx.com,hcsc.com|BCBS Illinois,BCBS Texas|insurance
Highmark|highmark.com||insurance
Independence Blue Cross|ibx.com||insurance
Florida Blue|floridablue.com||insurance
Horizon BCBS|horizonblue.com||insurance
Premera|premera.com||insurance
Regence|regence.com||insurance
Oscar Health|hioscar.com||insurance
Bright Health|brighthealthcare.com||insurance
Clover Health|cloverhealth.com||insurance
Alignment Healthcare|alignmenthealth.com||insurance
WellCare|wellcare.com||insurance
Delta Dental|deltadental.com||insurance
MetLife Dental|metlife.com||insurance
VSP Vision Care|vsp.com||insurance
EyeMed|eyemed.com||insurance
Shell plc|shell.com,shell.us|Shell|energy
BP|bp.com||energy
TotalEnergies|total.com,totalenergies.com|Total|energy
Eni|eni.com||energy
Equinor|equinor.com||energy
Repsol|repsol.com||energy
OMV|omv.com||energy
Saudi Aramco|aramco.com|Aramco|energy
ADNOC|adnoc.ae||energy
QatarEnergy|qatarenergy.qa||energy
Petrobras|petrobras.com.br||energy
Pemex|pemex.com||energy
Ecopetrol|ecopetrol.com.co||energy
PetroChina|petrochina.com.cn||energy
Sinopec|sinopec.com||energy
CNOOC|cnooc.com.cn||energy
Gazprom|gazprom.com,gazprom.ru||energy
Rosneft|rosneft.com,rosneft.ru||energy
Lukoil|lukoil.com,lukoil.ru||energy
Reliance Industries|reliance.com,ril.com||energy
Indian Oil|iocl.com||energy
Bharat Petroleum|bharatpetroleum.in||energy
Hindustan Petroleum|hindustanpetroleum.com||energy
ONGC|ongcindia.com||energy
Petronas|petronas.com||energy
PTT|pttplc.com||energy
Pertamina|pertamina.com||energy
Woodside Energy|woodside.com||energy
Santos|santos.com||energy
Suncor Energy|petro-canada.ca,suncor.com|Petro-Canada|energy
Canadian Natural Resources|cnrl.com||energy
Cenovus|cenovus.com||energy
Imperial Oil|esso.ca,imperialoil.ca|Esso|energy
Enbridge|enbridge.com||energy
TC Energy|tcenergy.com||energy
Kinder Morgan|kindermorgan.com||energy
Williams Companies|williams.com||energy
Enterprise Products|enterpriseproducts.com||energy
Plains All American|plains.com||energy
MPLX|mplx.com||energy
Cheniere|cheniere.com||energy
EOG Resources|eogresources.com||energy
Pioneer Natural Resources|pxd.com||energy
Devon Energy|devonenergy.com||energy
Diamondback Energy|diamondbackenergy.com||energy
Hess|hess.com||energy
Marathon Oil|marathonoil.com||energy
APA Corporation|apacorp.com|Apache|energy
Coterra|coterra.com||energy
Chesapeake Energy|chk.com||energy
Expand Energy|expandenergy.com||energy
Baker Hughes|bakerhughes.com||energy
SLB|slb.com|Schlumberger|energy
NOV|nov.com||energy
Transocean|deepwater.com||energy
Peabody Energy|peabodyenergy.com||energy
Arch Resources|archrsc.com||energy
NextEra Energy|fpl.com,nexteraenergy.com|Florida Power & Light,FPL|utilities
Dominion Energy|dominionenergy.com||utilities
American Electric Power|aep.com|AEP|utilities
Xcel Energy|xcelenergy.com||utilities
Consolidated Edison|coned.com|Con Edison|utilities
PG&E|pge.com|Pacific Gas and Electric|utilities
Edison International|edison.com,sce.com|Southern California Edison|utilities
Sempra|sdge.com,sempra.com,socalgas.com|SDG&E,SoCalGas|utilities
Entergy|entergy.com||utilities
FirstEnergy|firstenergycorp.com||utilities
PPL Corporation|pplelectric.com,pplweb.com|PPL Electric|utilities
Eversource|eversource.com||utilities
National Grid|nationalgrid.com,nationalgridus.com||utilities
Ameren|ameren.com||utilities
CenterPoint Energy|centerpointenergy.com||utilities
DTE Energy|dteenergy.com||utilities
CMS Energy|cmsenergy.com,consumersenergy.com|Consumers Energy|utilities
WEC Energy|we-energies.com,wecenergygroup.com|We Energies|utilities
Evergy|evergy.com||utilities
Alliant Energy|alliantenergy.com||utilities
NiSource|nipsco.com,nisource.com|NIPSCO|utilities
Atmos Energy|atmosenergy.com||utilities
Pinnacle West|aps.com,pinnaclewest.com|Arizona Public Service,APS|utilities
Portland General Electric|portlandgeneral.com||utilities
Puget Sound Energy|pse.com||utilities
Hawaiian Electric|hawaiianelectric.com||utilities
Tennessee Valley Authority|tva.com,tva.gov|TVA|utilities
Salt River Project|srpnet.com|SRP|utilities
LADWP|ladwp.com||utilities
Austin Energy|austinenergy.com||utilities
CPS Energy|cpsenergy.com||utilities
JEA|jea.com||utilities
American Water Works|amwater.com|American Water|utilities
Essential Utilities|aquaamerica.com,essential.co|Aqua|utilities
Waste Management|wm.com|WM|utilities
Republic Services|republicservices.com||utilities
Waste Connections|wasteconnections.com||utilities
Constellation Energy|constellation.com,constellationenergy.com|Constellation|utilities
Vistra|txu.com,vistracorp.com|TXU Energy|utilities
NRG Energy|nrg.com,reliant.com|Reliant|utilities
Direct Energy|directenergy.com||utilities
Just Energy|justenergy.com||utilities
EDF|edf.fr,edfenergy.com|EDF Energy|utilities
Engie|engie.com||utilities
Enel|enel.com,enel.it,enelgreenpower.com||utilities
Iberdrola|iberdrola.com,iberdrola.es,scottishpower.co.uk|Scottish Power|utilities
E.ON|eon.com,eon.de,eonenergy.com||utilities
RWE|rwe.com||utilities
Uniper|uniper.energy||utilities
Vattenfall|vattenfall.com,vattenfall.se||utilities
Orsted|orsted.com||utilities
Fortum|fortum.com||utilities
Centrica|britishgas.co.uk,centrica.com|British Gas|utilities
SSE|ovoenergy.com,sse.com,sseenergyservices.com|OVO Energy|utilities
Octopus Energy|octopus.energy||utilities
EDP|edp.com,edp.pt||utilities
Naturgy|naturgy.com||utilities
Endesa|endesa.com||utilities
A2A|a2a.eu||utilities
Hydro One|hydroone.com||utilities
Ontario Power Generation|opg.com||utilities
Hydro-Quebec|hydroquebec.com||utilities
BC Hydro|bchydro.com||utilities
Fortis Inc|fortisbc.com,fortisinc.com||utilities
Emera|emera.com,novascotiapower.ca|Nova Scotia Power|utilities
AGL Energy|agl.com.au||utilities
Origin Energy|originenergy.com.au||utilities
EnergyAustralia|energyaustralia.com.au||utilities
Ausgrid|ausgrid.com.au||utilities
Tokyo Electric Power|tepco.co.jp|TEPCO|utilities
Kansai Electric|kepco.co.jp||utilities
Korea Electric Power|kepco.co.kr|KEPCO|utilities
State Grid|sgcc.com.cn||utilities
General Electric|ge.com,geaerospace.com,gevernova.com|GE,GE Aerospace,GE Vernova|industrial
Emerson Electric|emerson.com||industrial
Eaton|eaton.com||industrial
Parker Hannifin|parker.com||industrial
Illinois Tool Works|itw.com|ITW|industrial
Rockwell Automation|rockwellautomation.com||industrial
Johnson Controls|johnsoncontrols.com||industrial
Carrier|carrier.com||industrial
Trane Technologies|trane.com,tranetechnologies.com|Trane|industrial
Lennox|lennox.com||industrial
Otis|otis.com||industrial
Schindler|schindler.com||industrial
Kone|kone.com||industrial
ThyssenKrupp|thyssenkrupp.com||industrial
ABB|abb.com||industrial
Schneider Electric|apc.com,schneider-electric.com,se.com|APC|industrial
Legrand|legrand.com,legrand.us||industrial
Hubbell|hubbell.com||industrial
Ametek|ametek.com||industrial
Roper Technologies|ropertech.com||industrial
Dover|dovercorporation.com||industrial
Fortive|fluke.com,fortive.com|Fluke|industrial
Xylem|xylem.com||industrial
Pentair|pentair.com||industrial
Ingersoll Rand|irco.com||industrial
Stanley Black & Decker|craftsman.com,dewalt.com,stanleyblackanddecker.com|DeWalt,Craftsman|industrial
Snap-on|snapon.com||industrial
Techtronic Industries|milwaukeetool.com,ryobitools.com,ttigroup.com|Milwaukee Tool,Ryobi|industrial
Makita|makita.com,makitatools.com||industrial
Bosch|bosch-home.com,bosch.com,boschtools.com|Robert Bosch|industrial
Hilti|hilti.com||industrial
Husqvarna|husqvarna.com||industrial
Toro|thetorocompany.com,toro.com||industrial
Briggs & Stratton|briggsandstratton.com||industrial
Generac|generac.com||industrial
Whirlpool|kitchenaid.com,maytag.com,whirlpool.com,whirlpoolcorp.com|Maytag,KitchenAid|consumer
Electrolux|electrolux.com,frigidaire.com|Frigidaire|consumer
Haier|geappliances.com,haier.com|GE Appliances|consumer
Miele|miele.com,miele.de||consumer
Dyson|dyson.co.uk,dyson.com||consumer
SharkNinja|ninjakitchen.com,sharkclean.com,sharkninja.com|Shark,Ninja|consumer
iRobot|irobot.com||consumer
Breville|breville.com||consumer
Cuisinart|cuisinart.com||consumer
Keurig Dr Pepper|drpepper.com,keurig.com,keurigdrpepper.com|Keurig,Dr Pepper|consumer
Nespresso|nespresso.com||consumer
Weber|weber.com||consumer
Traeger|traeger.com||consumer
Yeti|yeti.com||consumer
Stanley 1913|stanley1913.com||consumer
Peloton|onepeloton.com||consumer
NordicTrack|ifit.com,nordictrack.com|iFIT|consumer
Bowflex|bowflex.com||consumer
Tempur Sealy|sealy.com,tempurpedic.com,tempursealy.com|Tempur-Pedic,Sealy|consumer
Sleep Number|sleepnumber.com||consumer
Purple|purple.com||consumer
Casper|casper.com||consumer
La-Z-Boy|la-z-boy.com||consumer
Ashley Furniture|ashleyfurniture.com||consumer
IKEA|ikea.co.uk,ikea.com,ikea.us||retail
Wayfair LLC|wayfair.com||retail
Crate & Barrel|cb2.com,crateandbarrel.com|CB2|retail
Bed Bath & Beyond|bedbathandbeyond.com||retail
HomeGoods|homegoods.com||retail
"""

    private const val CHUNK_1 = """
At Home|athome.com||retail
Kirkland's|kirklands.com||retail
Pier 1|pier1.com||retail
Procter & Gamble|gillette.com,pampers.com,pg.com,tide.com|P&G,Pampers,Tide,Gillette|consumer
Unilever|dove.com,unilever.com||consumer
Colgate-Palmolive|colgate.com,colgatepalmolive.com||consumer
Kimberly-Clark|huggies.com,kimberly-clark.com,kleenex.com|Huggies,Kleenex|consumer
Clorox|clorox.com,thecloroxcompany.com||consumer
Church & Dwight|armandhammer.com,churchdwight.com|Arm & Hammer|consumer
Reckitt|reckitt.com||consumer
Henkel|henkel.com||consumer
L'Oreal|loreal.com,lorealparisusa.com||consumer
Estee Lauder|clinique.com,elcompanies.com,esteelauder.com,maccosmetics.com|Clinique,MAC Cosmetics|consumer
Coty|coty.com||consumer
Shiseido|shiseido.com||consumer
Revlon|revlon.com||consumer
e.l.f. Beauty|elfbeauty.com,elfcosmetics.com|e.l.f. Cosmetics|consumer
Bath & Body Works Inc|bathandbodyworks.com||consumer
Nestle|nestle.com,nestleusa.com,purina.com|Purina|food
Danone|danone.com||food
Kellanova|kellanova.com,kelloggs.com|Kellogg's|food
WK Kellogg|wkkellogg.com||food
General Mills|generalmills.com||food
Campbell's|campbells.com,campbellsoupcompany.com|Campbell Soup|food
Conagra Brands|conagrabrands.com|Conagra|food
J.M. Smucker|jmsmucker.com,smucker.com|Smucker's|food
Hershey|hersheys.com,thehersheycompany.com||food
Mars Inc|mars.com|Mars|food
Ferrero|ferrero.com||food
Hormel Foods|hormelfoods.com||food
McCormick|mccormick.com,mccormickcorporation.com||food
Land O'Lakes|landolakes.com,landolakesinc.com||food
Dean Foods|deanfoods.com||food
Lactalis|lactalis.com||food
Kerry Group|kerry.com||food
Associated British Foods|abf.co.uk,primark.com|Primark|food
Cargill|cargill.com||agriculture
Bunge|bunge.com||agriculture
Corteva|corteva.com||agriculture
Nutrien|nutrien.com||agriculture
Mosaic|mosaicco.com||agriculture
CF Industries|cfindustries.com||agriculture
Scotts Miracle-Gro|scotts.com,scottsmiraclegro.com||consumer
Tractor Supply Company|tractorsupply.com||retail
Anheuser-Busch InBev|ab-inbev.com,anheuser-busch.com,budweiser.com|Anheuser-Busch,Budweiser|consumer
Molson Coors|coors.com,molsoncoors.com|Coors|consumer
Heineken|heineken.com,theheinekencompany.com||consumer
Carlsberg|carlsberg.com,carlsberggroup.com||consumer
Diageo|diageo.com||consumer
Pernod Ricard|pernod-ricard.com||consumer
Constellation Brands|cbrands.com,coronausa.com|Corona|consumer
Brown-Forman|brown-forman.com,jackdaniels.com|Jack Daniel's|consumer
Bacardi|bacardi.com,bacardilimited.com||consumer
Boston Beer|bostonbeer.com,samueladams.com|Samuel Adams|consumer
Monster Beverage|monsterbevcorp.com,monsterenergy.com|Monster Energy|consumer
Red Bull|redbull.com||consumer
Celsius|celsius.com||consumer
Philip Morris International|pmi.com||consumer
Altria|altria.com||consumer
British American Tobacco|bat.com|BAT|consumer
Imperial Brands|imperialbrandsplc.com||consumer
Japan Tobacco|jt.com||consumer
McDonald's Corporation|mcdonalds.com||food
Yum Brands|kfc.com,pizzahut.com,tacobell.com,yum.com|KFC,Taco Bell,Pizza Hut|food
Restaurant Brands International|bk.com,popeyes.com,rbi.com,timhortons.com|Burger King,Tim Hortons,Popeyes|food
Wendy's|wendys.com||food
Chipotle|chipotle.com||food
Domino's|dominos.com||food
Papa John's|papajohns.com||food
Subway|subway.com||food
Darden Restaurants|darden.com,longhornsteakhouse.com,olivegarden.com|Olive Garden,LongHorn Steakhouse|food
Texas Roadhouse|texasroadhouse.com||food
Bloomin' Brands|bloominbrands.com,outback.com|Outback Steakhouse|food
Brinker|brinker.com,chilis.com|Chili's|food
Cracker Barrel|crackerbarrel.com||food
Denny's|dennys.com||food
IHOP|dinebrands.com,ihop.com|Dine Brands,Applebee's|food
Dutch Bros|dutchbros.com||food
Panera Bread|panerabread.com||food
Sonic Drive-In|sonicdrivein.com||food
Jack in the Box|jackinthebox.com||food
Arby's|arbys.com,inspirebrands.com|Inspire Brands|food
Dairy Queen|dairyqueen.com||food
Wingstop|wingstop.com||food
Raising Cane's|raisingcanes.com||food
Shake Shack|shakeshack.com||food
Five Guys|fiveguys.com||food
In-N-Out Burger|in-n-out.com||food
Whataburger|whataburger.com||food
Culver's|culvers.com||food
Zaxby's|zaxbys.com||food
Bojangles|bojangles.com||food
Krispy Kreme|krispykreme.com||food
Tim Hortons Inc|timhortons.ca,timhortons.com||food
Toyota Motor|global.toyota,lexus.com,toyota.com,toyota.jp,toyotafinancial.com|Toyota,Lexus,Toyota Financial Services|auto
Honda Motor|acura.com,global.honda,honda.com,hondafinancialservices.com|Honda,Acura|auto
Nissan Motor|infinitiusa.com,nissan-global.com,nissan.co.uk,nissanusa.com|Nissan,Infiniti|auto
Subaru|subaru.com,subaru.jp||auto
Mazda|mazda.com,mazdausa.com||auto
Mitsubishi Motors|mitsubishi-motors.com,mitsubishicars.com||auto
Suzuki|globalsuzuki.com,suzuki.co.jp||auto
Hyundai Motor|genesis.com,hyundai.com,hyundaiusa.com|Hyundai,Genesis|auto
Kia|kia.com,kia.us||auto
Volkswagen Group|audi.com,audiusa.com,bentleymotors.com,lamborghini.com,porsche.com,seat.com,skoda-auto.com,volkswagen.com,volkswagen.de,vw.com,vwgroup.com|Volkswagen,VW,Audi,Porsche,Skoda,SEAT,Bentley,Lamborghini|auto
Mercedes-Benz|mbusa.com,mercedes-benz.com,mercedes-benz.de|Mercedes|auto
BMW Group|bmw.com,bmwgroup.com,bmwusa.com,mini.com,rolls-roycemotorcars.com|BMW,MINI,Rolls-Royce Motor Cars|auto
Stellantis|alfaromeo.com,chrysler.com,citroen.com,dodge.com,fiat.com,jeep.com,maserati.com,opel.com,peugeot.com,ram.com,stellantis.com|Jeep,Ram,Dodge,Chrysler,Fiat,Peugeot,Citroen,Opel,Alfa Romeo,Maserati|auto
Renault|dacia.com,renault.com,renaultgroup.com|Dacia|auto
Volvo Cars|volvocars.com||auto
Volvo Group|mack.com,volvogroup.com,volvotrucks.com|Volvo Trucks,Mack Trucks|auto
Polestar|polestar.com||auto
Jaguar Land Rover|jaguar.com,jaguarlandrover.com,landrover.com|Jaguar,Land Rover|auto
Aston Martin|astonmartin.com||auto
McLaren|mclaren.com||auto
Ferrari|ferrari.com||auto
Rivian|rivian.com||auto
Lucid Motors|lucidmotors.com||auto
Fisker|fisker.com||auto
BYD|byd.com,bydglobal.com||auto
Geely|geely.com,zeekrglobal.com|Zeekr|auto
NIO|nio.com||auto
XPeng|xpeng.com||auto
Li Auto|lixiang.com||auto
Great Wall Motor|gwm-global.com||auto
Tata Motors|tatamotors.com||auto
Mahindra|mahindra.com||auto
Maruti Suzuki|marutisuzuki.com||auto
Harley-Davidson|harley-davidson.com||auto
Polaris|polaris.com||auto
BRP|brp.com||auto
Yamaha Motor|yamaha-motor.com||auto
Kawasaki|kawasaki.com||auto
Ducati|ducati.com||auto
Triumph Motorcycles|triumphmotorcycles.com||auto
CarMax|carmax.com||auto
Carvana Co|carvana.com||auto
AutoNation|autonation.com||auto
Penske Automotive|penskeautomotive.com||auto
Lithia Motors|lithia.com||auto
Group 1 Automotive|group1auto.com||auto
Sonic Automotive|sonicautomotive.com||auto
Cars.com|cars.com||auto
Autotrader Inc|autotrader.com||auto
Kelley Blue Book|kbb.com||auto
Edmunds|edmunds.com||auto
TrueCar|truecar.com||auto
CarGurus|cargurus.com||auto
Copart|copart.com||auto
IAA|iaai.com||auto
Manheim|manheim.com||auto
Cox Automotive|coxautoinc.com||auto
Michelin|michelin.com||auto
Bridgestone|bridgestone.com,bridgestoneamericas.com,firestone.com|Firestone|auto
Goodyear|goodyear.com||auto
Continental AG|continental-tires.com,continental.com|Continental|auto
Pirelli|pirelli.com||auto
Discount Tire|discounttire.com||auto
Les Schwab|lesschwab.com||auto
Pep Boys|pepboys.com||auto
Jiffy Lube|jiffylube.com||auto
Valvoline|valvoline.com||auto
Midas|midas.com||auto
Firestone Complete Auto Care|firestonecompleteautocare.com||auto
Safelite|safelite.com||auto
Aptiv|aptiv.com||auto
Magna International|magna.com||auto
Lear Corporation|lear.com||auto
BorgWarner|borgwarner.com||auto
Denso|denso.com||auto
Aisin|aisin.com||auto
ZF|zf.com||auto
Valeo|valeo.com||auto
Autoliv|autoliv.com||auto
Gentex|gentex.com||auto
Garrett Motion|garrettmotion.com||auto
LKQ|lkqcorp.com||auto
Walt Disney Company|abc.com,disney.com,disneyplus.com,espn.com,go.com,hulu.com,marvel.com,pixar.com|Disney,ESPN,Hulu,ABC,Marvel|media
Warner Bros. Discovery|cnn.com,discovery.com,hbo.com,max.com,warnerbros.com,wbd.com|Warner Bros,HBO,Max,CNN,Discovery|media
Paramount Global|cbs.com,cbsnews.com,mtv.com,nick.com,paramount.com,paramountplus.com,pluto.tv|Paramount,CBS,MTV,Nickelodeon,Pluto TV|media
NBCUniversal|nbc.com,nbcnews.com,nbcuni.com,peacocktv.com,universalpictures.com|NBC,Universal Pictures,Peacock|media
Fox Corporation|fox.com,foxbusiness.com,foxnews.com,foxsports.com,tubi.tv|Fox,Fox News,Tubi|media
Sony Pictures|sonypictures.com||media
Lionsgate|lionsgate.com,starz.com|Starz|media
AMC Networks|amc.com,amcnetworks.com|AMC|media
AMC Theatres|amctheatres.com|AMC Entertainment|media
Cinemark|cinemark.com||media
Regal Cinemas|regmovies.com||media
IMAX|imax.com||media
Spotify Technology|spotify.com||media
SiriusXM|pandora.com,siriusxm.com|Pandora|media
iHeartMedia|iheart.com,iheartmedia.com|iHeartRadio|media
Audacy|audacy.com||media
Cumulus Media|cumulusmedia.com||media
Deezer|deezer.com||media
Tidal|tidal.com||media
SoundCloud|soundcloud.com||media
Bandcamp|bandcamp.com||media
Audible Inc|audible.com||media
Scribd|everand.com,scribd.com|Everand|media
Kindle Direct Publishing|kdp.amazon.com||media
New York Times|nyt.com,nytimes.com||media
Washington Post|washingtonpost.com||media
Wall Street Journal|dowjones.com,wsj.com|Dow Jones|media
News Corp|news.com.au,newscorp.com|News Corporation|media
Gannett|gannett.com,usatoday.com|USA Today|media
Lee Enterprises|lee.net||media
Hearst|hearst.com||media
Conde Nast|condenast.com,newyorker.com,vogue.com,wired.com|The New Yorker,Vogue,Wired|media
Meredith|dotdashmeredith.com,meredith.com|Dotdash Meredith|media
Bloomberg|bloomberg.com,bloomberg.net||media
Reuters|reuters.com,thomsonreuters.com|Thomson Reuters|media
Associated Press|ap.org|AP|media
BBC|bbc.co.uk,bbc.com||media
The Guardian|guardian.co.uk,theguardian.com||media
Daily Mail|dailymail.co.uk||media
The Times|thetimes.co.uk,thetimes.com||media
Financial Times|ft.com||media
The Economist|economist.com||media
Telegraph|telegraph.co.uk||media
Reach plc|mirror.co.uk,reachplc.com|Daily Mirror|media
ITV|itv.com||media
Channel 4|channel4.com||media
CBC|cbc.ca,radio-canada.ca|Radio-Canada|media
Globe and Mail|theglobeandmail.com||media
Toronto Star|thestar.com||media
Postmedia|postmedia.com||media
CTV|bellmedia.ca,ctv.ca|Bell Media|media
Global News|globalnews.ca||media
ABC Australia|abc.net.au||media
Nine Entertainment|nine.com.au||media
Seven West Media|sevenwestmedia.com.au||media
Al Jazeera|aljazeera.com,aljazeera.net||media
Axel Springer|axelspringer.com,bild.de|Bild|media
Bertelsmann|bertelsmann.com,penguinrandomhouse.com,rtl.com|RTL,Penguin Random House|media
Vivendi|canalplus.com,vivendi.com|Canal+|media
HarperCollins|harpercollins.com||media
Simon & Schuster|simonandschuster.com||media
Hachette|hachette.com,hachettebookgroup.com||media
Macmillan|macmillan.com||media
Vox Media|theverge.com,vox.com,voxmedia.com|The Verge|media
BuzzFeed|buzzfeed.com||media
Vice Media|vice.com||media
Politico|politico.com||media
Axios|axios.com||media
The Atlantic|theatlantic.com||media
Forbes|forbes.com||media
Fortune|fortune.com||media
Business Insider|businessinsider.com,insider.com||media
CNBC|cnbc.com||media
MSNBC|msnbc.com||media
NPR|npr.org||media
PBS|pbs.org||media
C-SPAN|c-span.org||media
TechCrunch|techcrunch.com||media
Engadget|engadget.com||media
Ars Technica|arstechnica.com||media
CNET|cnet.com||media
ZDNet|zdnet.com||media
Tom's Hardware|tomshardware.com||media
PCMag|pcmag.com||media
Mashable|mashable.com||media
Gizmodo|gizmodo.com||media
IGN|ign.com||media
GameSpot|gamespot.com||media
Ticketmaster Entertainment|livenation.com,ticketmaster.com|Live Nation|media
StubHub Inc|stubhub.com||media
SeatGeek|seatgeek.com||media
Vivid Seats|vividseats.com||media
AXS|axs.com||media
Fandango|fandango.com||media
Atom Tickets|atomtickets.com||media
NFL|nfl.com||sports
NBA|nba.com||sports
MLB|mlb.com||sports
NHL|nhl.com||sports
MLS|mlssoccer.com||sports
NASCAR|nascar.com||sports
PGA Tour|pgatour.com||sports
UFC|ufc.com||sports
WWE|wwe.com||sports
FIFA|fifa.com||sports
UEFA|uefa.com||sports
Premier League|premierleague.com||sports
NCAA|ncaa.com,ncaa.org||sports
ESPN Inc|espn.com||sports
DraftKings|draftkings.com||sports
FanDuel|fanduel.com||sports
BetMGM|betmgm.com||sports
Caesars Entertainment|caesars.com||travel
MGM Resorts|mgmresorts.com||travel
Wynn Resorts|wynnlasvegas.com,wynnresorts.com||travel
Las Vegas Sands|sands.com||travel
Penn Entertainment|pennentertainment.com||travel
Hard Rock|hardrock.com||travel
Adidas|adidas.co.uk,adidas.com,adidas.de||apparel
Puma|puma.com||apparel
Under Armour|underarmour.com||apparel
Lululemon|lululemon.com||apparel
VF Corporation|thenorthface.com,timberland.com,vans.com,vfc.com|The North Face,Vans,Timberland|apparel
Levi Strauss|levi.co.uk,levi.com,levistrauss.com|Levi's|apparel
PVH|calvinklein.com,pvh.com,tommy.com|Calvin Klein,Tommy Hilfiger|apparel
Ralph Lauren|ralphlauren.com||apparel
Tapestry|coach.com,katespade.com,tapestry.com|Coach,Kate Spade|apparel
Capri Holdings|capriholdings.com,jimmychoo.com,michaelkors.com,versace.com|Michael Kors,Versace,Jimmy Choo|apparel
Hanesbrands|hanes.com,hanesbrands.com|Hanes|apparel
Carter's|carters.com,oshkosh.com|OshKosh B'gosh|apparel
Children's Place|childrensplace.com||apparel
Abercrombie & Fitch|abercrombie.com,hollisterco.com|Hollister|apparel
American Eagle Outfitters|ae.com,aerie.com|American Eagle,Aerie|apparel
Urban Outfitters|anthropologie.com,freepeople.com,urbanoutfitters.com,urbn.com|Anthropologie,Free People|apparel
J.Crew|jcrew.com||apparel
Express|express.com||apparel
Chico's|chicos.com||apparel
Ann Taylor|anntaylor.com,loft.com|LOFT|apparel
Talbots|talbots.com||apparel
Lands' End|landsend.com||apparel
L.L.Bean|llbean.com||apparel
Eddie Bauer|eddiebauer.com||apparel
Columbia Sportswear|columbia.com||apparel
Patagonia|patagonia.com||apparel
Canada Goose|canadagoose.com||apparel
Arc'teryx|arcteryx.com||apparel
REI|rei.com||retail
Bass Pro Shops|basspro.com,cabelas.com|Cabela's|retail
Skechers|skechers.com||apparel
Crocs|crocs.com||apparel
Deckers Brands|deckers.com,hokaoneone.com,ugg.com|UGG,HOKA|apparel
New Balance|newbalance.com||apparel
Asics|asics.com||apparel
On Running|on.com||apparel
Allbirds|allbirds.com||apparel
Dr. Martens|drmartens.com||apparel
Clarks|clarks.co.uk,clarks.com||apparel
Wolverine World Wide|wolverineworldwide.com||apparel
Steve Madden|stevemadden.com||apparel
DSW|dsw.com||retail
Famous Footwear|famousfootwear.com||retail
Zara|inditex.com,zara.com|Inditex|apparel
H&M|hm.com||apparel
Uniqlo|fastretailing.com,uniqlo.com|Fast Retailing|apparel
Shein Group|shein.com||apparel
Primark Stores|primark.com||apparel
Next plc|next.co.uk|Next|apparel
Marks & Spencer|marks-and-spencer.com,marksandspencer.com|M&S|retail
ASOS|asos.com||apparel
Boohoo|boohoo.com||apparel
Zalando|zalando.co.uk,zalando.com,zalando.de||apparel
Farfetch|farfetch.com||apparel
Net-a-Porter|net-a-porter.com||apparel
Nordstrom Rack|nordstromrack.com||retail
Saks Fifth Avenue|saks.com,saksfifthavenue.com|Saks|retail
Neiman Marcus|neimanmarcus.com||retail
Bergdorf Goodman|bergdorfgoodman.com||retail
Harrods|harrods.com||retail
Selfridges|selfridges.com||retail
John Lewis Partnership|johnlewis.com,waitrose.com|Waitrose|retail
Galeries Lafayette|galerieslafayette.com||retail
El Corte Ingles|elcorteingles.es||retail
LVMH|dior.com,louisvuitton.com,lvmh.com,sephora.com,tiffany.com|Louis Vuitton,Dior,Tiffany & Co|luxury
Kering|balenciaga.com,bottegaveneta.com,gucci.com,kering.com,ysl.com|Gucci,Saint Laurent,Balenciaga,Bottega Veneta|luxury
Hermes|hermes.com||luxury
Chanel|chanel.com||luxury
Prada|miumiu.com,prada.com|Miu Miu|luxury
Burberry|burberry.com||luxury
Richemont|cartier.com,iwc.com,montblanc.com,richemont.com|Cartier,Montblanc,IWC|luxury
Rolex|rolex.com||luxury
Swatch Group|longines.com,omegawatches.com,swatch.com,swatchgroup.com,tissotwatches.com|Swatch,Omega,Longines,Tissot|luxury
Patek Philippe|patek.com||luxury
Audemars Piguet|audemarspiguet.com||luxury
Tag Heuer|tagheuer.com||luxury
Breitling|breitling.com||luxury
Fossil|fossil.com||apparel
Movado|movado.com||apparel
Signet Jewelers|jared.com,kay.com,signetjewelers.com,zales.com|Kay Jewelers,Zales,Jared|retail
Pandora Jewelry|pandora.net||retail
Blue Nile|bluenile.com||retail
Brilliant Earth|brilliantearth.com||retail
Prologis|prologis.com||realestate
Simon Property Group|simon.com||realestate
Brookfield|brookfield.com||realestate
Blackstone|blackstone.com||finance
KKR|kkr.com||finance
Carlyle Group|carlyle.com||finance
Apollo Global Management|apollo.com||finance
TPG|tpg.com||finance
Bain Capital|baincapital.com||finance
Realtor.com|move.com,realtor.com||realestate
Zillow|trulia.com,zillow.com|Trulia|realestate
Redfin|redfin.com||realestate
Opendoor|opendoor.com||realestate
Offerpad|offerpad.com||realestate
Compass|compass.com||realestate
eXp Realty|exprealty.com||realestate
Keller Williams|kw.com||realestate
RE/MAX|remax.com||realestate
Coldwell Banker|coldwellbanker.com||realestate
Century 21|century21.com||realestate
Sotheby's International Realty|sothebysrealty.com||realestate
Anywhere Real Estate|anywhere.re||realestate
Douglas Elliman|elliman.com||realestate
Howard Hanna|howardhanna.com||realestate
Apartments.com|apartments.com,costar.com|CoStar|realestate
Rent.com|rent.com||realestate
Zumper|zumper.com||realestate
Rightmove|rightmove.co.uk||realestate
Zoopla|zoopla.co.uk||realestate
Purplebricks|purplebricks.co.uk||realestate
Foxtons|foxtons.co.uk||realestate
Savills|savills.com||realestate
Knight Frank|knightfrank.com||realestate
JLL|jll.com|Jones Lang LaSalle|realestate
Cushman & Wakefield|cushmanwakefield.com||realestate
Colliers|colliers.com||realestate
Greystar|greystar.com||realestate
AvalonBay|avalonbay.com||realestate
Equity Residential|equityapartments.com||realestate
Invitation Homes|invitationhomes.com||realestate
American Homes 4 Rent|amh.com||realestate
Public Storage|publicstorage.com||realestate
Extra Space Storage|extraspace.com||realestate
CubeSmart|cubesmart.com||realestate
U-Haul|uhaul.com||transport
Penske|gopenske.com,pensketruckrental.com||transport
Ryder|ryder.com||transport
Budget Truck Rental|budgettruck.com||transport
PODS|pods.com||transport
Deutsche Post DHL|deutschepost.de,dhl.com,dpdhl.com|DHL Group,Deutsche Post|shipping
DPD|dpd.co.uk,dpd.com||shipping
GLS|gls-group.com||shipping
Hermes Germany|myhermes.de||shipping
Yodel|yodel.co.uk||shipping
Parcelforce|parcelforce.com||shipping
Australia Post|auspost.com.au||shipping
NZ Post|nzpost.co.nz||shipping
La Poste|laposte.fr||shipping
PostNL|postnl.nl||shipping
Bpost|bpost.be||shipping
Poste Italiane|poste.it||shipping
Correos|correos.es||shipping
Japan Post|japanpost.jp,post.japanpost.jp||shipping
Yamato Transport|kuronekoyamato.co.jp||shipping
SF Express|sf-express.com||shipping
Cainiao|cainiao.com||shipping
Aramex|aramex.com||shipping
OnTrac|ontrac.com||shipping
LaserShip|lasership.com||shipping
Pitney Bowes|pitneybowes.com||shipping
Stamps.com|stamps.com||shipping
ShipStation|shipstation.com||shipping
Shippo|goshippo.com||shipping
EasyPost|easypost.com||shipping
XPO|xpo.com||transport
GXO Logistics|gxo.com||transport
RXO|rxo.com||transport
J.B. Hunt|jbhunt.com||transport
Schneider National|schneider.com||transport
Knight-Swift|knight-swift.com||transport
Werner Enterprises|werner.com||transport
Old Dominion Freight Line|odfl.com||transport
Saia|saia.com||transport
Estes Express|estes-express.com||transport
ArcBest|arcb.com||transport
Landstar|landstar.com||transport
C.H. Robinson|chrobinson.com||transport
Expeditors|expeditors.com||transport
Kuehne+Nagel|kuehne-nagel.com||transport
DSV|dsv.com||transport
DB Schenker|dbschenker.com||transport
Maersk|maersk.com||transport
MSC|msc.com||transport
CMA CGM|cma-cgm.com||transport
Hapag-Lloyd|hapag-lloyd.com||transport
Evergreen Marine|evergreen-marine.com||transport
COSCO Shipping|coscoshipping.com||transport
Canadian National Railway|cn.ca|CN Rail|transport
Canadian Pacific Kansas City|cpkcr.com|CPKC|transport
BNSF Railway|bnsf.com|BNSF|transport
Deutsche Bahn|bahn.de,deutschebahn.com|DB|transport
SNCF|sncf-connect.com,sncf.com||transport
Eurostar|eurostar.com||transport
Trainline|thetrainline.com||transport
National Rail|nationalrail.co.uk||transport
Transport for London|tfl.gov.uk|TfL|transport
VIA Rail|viarail.ca||transport
JR East|jreast.co.jp||transport
Indian Railways|indianrail.gov.in,irctc.co.in|IRCTC|transport
FlixBus|flixbus.com||transport
Megabus|megabus.com||transport
Berkshire Hathaway Energy|brkenergy.com,midamericanenergy.com,pacificorp.com|PacifiCorp,MidAmerican Energy|utilities
Koch Industries|kochinc.com,kochind.com||industrial
Honeywell International|honeywell.com||industrial
Danaher Corporation|danaher.com||healthcare
Tata Group|tata.com||conglomerate
Reliance Retail|relianceretail.com||retail
Adani Group|adani.com||conglomerate
Aditya Birla Group|adityabirla.com||conglomerate
Mahindra Group|mahindra.com||conglomerate
Samsung Group|samsung.com||conglomerate
SK Group|sk.com||conglomerate
LG Group|lg.co.kr||conglomerate
Hyundai Motor Group|hyundaimotorgroup.com||conglomerate
Lotte|lotte.co.kr||conglomerate
Mitsubishi Corporation|mitsubishicorp.com||conglomerate
Mitsui & Co|mitsui.com||conglomerate
Sumitomo Corporation|sumitomocorp.com||conglomerate
Itochu|itochu.co.jp||conglomerate
Marubeni|marubeni.com||conglomerate
SoftBank Group|group.softbank||conglomerate
Alibaba Group|alibaba.com,alibabagroup.com,aliexpress.com,taobao.com,tmall.com|Alibaba,Taobao,Tmall,AliExpress|tech
Jardine Matheson|jardines.com||conglomerate
CK Hutchison|ckh.com.hk||conglomerate
Swire|swire.com,swirepacific.com||conglomerate
Sime Darby|simedarby.com||conglomerate
Wesfarmers|bunnings.com.au,kmart.com.au,officeworks.com.au,wesfarmers.com.au|Bunnings,Kmart Australia,Officeworks|conglomerate
Woolworths Group|bigw.com.au,woolworths.com.au,woolworthsgroup.com.au|Woolworths,Big W|retail
Coles Group|coles.com.au,colesgroup.com.au|Coles|retail
JB Hi-Fi|jbhifi.com.au||retail
Harvey Norman|harveynorman.com.au||retail
Myer|myer.com.au||retail
David Jones|davidjones.com||retail
Loblaw|loblaw.ca,loblaws.ca,pcfinancial.ca,shoppersdrugmart.ca|Loblaws,PC Financial,Shoppers Drug Mart|retail
Sobeys|empireco.ca,sobeys.com|Empire Company|retail
Metro Inc|metro.ca||retail
Canadian Tire|canadiantire.ca||retail
Hudson's Bay|hbc.com,thebay.com|The Bay|retail
Dollarama|dollarama.com||retail
Lululemon Athletica|lululemon.com||apparel
Tesco plc|tesco.com|Tesco|retail
J Sainsbury|about.sainsburys.co.uk,sainsburys.co.uk|Sainsbury's|retail
Asda Group|asda.com|ASDA|retail
Morrisons|morrisons.com||retail
Aldi UK|aldi.co.uk||retail
Waitrose & Partners|waitrose.com||retail
Co-op|coop.co.uk||retail
Iceland Foods|iceland.co.uk||retail
Ocado|ocado.com||retail
B&Q|diy.com|Kingfisher|retail
Screwfix|screwfix.com||retail
Wickes|wickes.co.uk||retail
Argos Ltd|argos.co.uk||retail
Currys plc|currys.co.uk||retail
Boots UK|boots.com||retail
Superdrug|superdrug.com||retail
WHSmith|whsmith.co.uk||retail
Halfords|halfords.com||retail
Dunelm|dunelm.com||retail
The Range|therange.co.uk||retail
Very|very.co.uk||retail
Carrefour|carrefour.com,carrefour.fr||retail
Auchan|auchan-retail.com,auchan.fr||retail
E.Leclerc|e.leclerc||retail
Casino Group|groupe-casino.fr||retail
Intermarche|intermarche.com||retail
Fnac Darty|darty.com,fnac.com|Fnac,Darty|retail
Cdiscount|cdiscount.com||retail
Decathlon|decathlon.com,decathlon.fr||retail
Leroy Merlin|leroymerlin.com,leroymerlin.fr||retail
Rewe Group|penny.de,rewe-group.com,rewe.de|REWE,Penny|retail
Edeka|edeka.de||retail
Kaufland|kaufland.com,kaufland.de||retail
dm-drogerie markt|dm.de||retail
Rossmann|rossmann.de||retail
Otto Group|otto.de,ottogroup.com||retail
MediaMarkt|mediamarkt.de,mediamarktsaturn.com|Saturn|retail
Douglas|douglas.de||retail
Ahold Delhaize|ah.nl,aholddelhaize.com,foodlion.com,giantfood.com,hannaford.com,stopandshop.com|Albert Heijn,Stop & Shop,Giant Food,Food Lion,Hannaford|retail
Jumbo|jumbo.com||retail
Bol.com|bol.com||retail
Coolblue|coolblue.nl||retail
Colruyt|colruyt.be||retail
Delhaize|delhaize.be||retail
Migros|migros.ch||retail
Coop Switzerland|coop.ch||retail
Mercadona|mercadona.es||retail
Esselunga|esselunga.it||retail
Conad|conad.it||retail
Coop Italia|coop.it||retail
Jeronimo Martins|jeronimomartins.com,pingodoce.pt|Pingo Doce|retail
Continente|continente.pt||retail
ICA|ica.se||retail
Coop Norway|coop.no||retail
Kesko|kesko.fi||retail
S Group|s-ryhma.fi||retail
Biedronka|biedronka.pl||retail
Allegro|allegro.pl||retail
Ozon|ozon.ru||retail
Wildberries|wildberries.ru||retail
Magnit|magnit.com||retail
X5 Group|x5.ru||retail
Aeon|aeon.com,aeon.info||retail
Seven & i Holdings|7-eleven.com,7andi.com|7-Eleven|retail
FamilyMart|family.co.jp||retail
Lawson|lawson.co.jp||retail
Don Quijote|donki.com||retail
Yodobashi Camera|yodobashi.com||retail
Bic Camera|biccamera.com||retail
Shinsegae|shinsegae.com,ssg.com|SSG|retail
Emart|emart.com||retail
Lotte Shopping|lotteon.com,lotteshopping.com||retail
Coupang Inc|coupang.com||retail
Suning|suning.com||retail
Gome|gome.com.cn||retail
Vipshop|vip.com||retail
Dangdang|dangdang.com||retail
Reliance Digital|reliancedigital.in||retail
DMart|dmartindia.com|Avenue Supermarts|retail
Big Bazaar|bigbazaar.com||retail
Nykaa|nykaa.com||retail
Meesho|meesho.com||retail
Snapdeal|snapdeal.com||retail
Shoprite|shoprite.co.za,shopriteholdings.co.za||retail
Pick n Pay|pnp.co.za||retail
Woolworths South Africa|woolworths.co.za||retail
Takealot|takealot.com||retail
Jumia|jumia.com||retail
Noon|noon.com||retail
Namshi|namshi.com||retail
Carrefour UAE|carrefouruae.com,majidalfuttaim.com|Majid Al Futtaim|retail
Falabella|falabella.com||retail
Cencosud|cencosud.com||retail
Magazine Luiza|magazineluiza.com.br|Magalu|retail
Americanas|americanas.com.br||retail
Casas Bahia|casasbahia.com.br||retail
Mercado Libre|mercadolibre.com,mercadolibre.com.ar,mercadolibre.com.mx,mercadolivre.com.br|MercadoLibre,Mercado Livre|retail
Liverpool|liverpool.com.mx||retail
Soriana|soriana.com||retail
Chedraui|chedraui.com.mx||retail
OXXO|oxxo.com||retail
Walmart de Mexico|walmart.com.mx,walmartmexico.com||retail
Coppel|coppel.com||retail
Elektra|elektra.mx||retail
Internal Revenue Service|irs.gov||government
Social Security Administration|ssa.gov||government
U.S. Department of the Treasury|fiscal.treasury.gov,treasury.gov||government
U.S. Postal Service|usps.com||government
Department of Veterans Affairs|va.gov|VA|government
Department of Homeland Security|dhs.gov||government
U.S. Citizenship and Immigration Services|uscis.gov|USCIS|government
Customs and Border Protection|cbp.gov|CBP|government
TSA|tsa.gov||government
FBI|fbi.gov||government
Department of Justice|justice.gov|DOJ|government
Department of State|state.gov,travel.state.gov||government
Department of Education|ed.gov||government
Department of Labor|dol.gov||government
Department of Health and Human Services|hhs.gov|HHS|government
Centers for Medicare & Medicaid Services|cms.gov,healthcare.gov,medicare.gov||government
CDC|cdc.gov||government
FDA|fda.gov||government
NIH|nih.gov||government
Federal Trade Commission|ftc.gov|FTC|government
FCC|fcc.gov||government
SEC|sec.gov|Securities and Exchange Commission|government
Federal Reserve|federalreserve.gov||government
FDIC|fdic.gov||government
Small Business Administration|sba.gov|SBA|government
FEMA|fema.gov||government
NASA|nasa.gov||government
USDA|usda.gov||government
EPA|epa.gov||government
Department of Defense|defense.gov||government
U.S. Army|army.mil||government
U.S. Navy|navy.mil||government
U.S. Air Force|af.mil||government
Login.gov|login.gov||government
ID.me|id.me||government
USA.gov|usa.gov||government
California DMV|dmv.ca.gov||government
Texas DPS|dps.texas.gov,txdmv.gov||government
New York DMV|dmv.ny.gov||government
Florida DHSMV|flhsmv.gov||government
GOV.UK|gov.uk,service.gov.uk||government
HM Revenue & Customs|hmrc.gov.uk||government
DVLA UK|dvla.gov.uk||government
DWP|dwp.gov.uk||government
Companies House|companieshouse.gov.uk||government
Canada Revenue Agency|canada.ca||government
Service Canada|canada.ca||government
Australian Taxation Office|ato.gov.au||government
Services Australia|my.gov.au,servicesaustralia.gov.au|myGov,Centrelink|government
Inland Revenue NZ|ird.govt.nz|IRD|government
European Commission|ec.europa.eu,europa.eu||government
Bundesagentur fur Arbeit|arbeitsagentur.de||government
impots.gouv.fr|impots.gouv.fr||government
Ameli|ameli.fr||government
Agenzia delle Entrate|agenziaentrate.gov.it||government
Agencia Tributaria|agenciatributaria.gob.es||government
Belastingdienst|belastingdienst.nl||government
United Nations|un.org||government
World Health Organization|who.int|WHO|government
Red Cross|icrc.org,ifrc.org,redcross.org||nonprofit
UNICEF|unicef.org||nonprofit
Salvation Army|salvationarmy.org,salvationarmyusa.org||nonprofit
Goodwill|goodwill.org||nonprofit
Habitat for Humanity|habitat.org||nonprofit
St. Jude Children's Research Hospital|stjude.org||nonprofit
American Cancer Society|cancer.org||nonprofit
American Heart Association|heart.org||nonprofit
Doctors Without Borders|doctorswithoutborders.org,msf.org|MSF|nonprofit
World Wildlife Fund|worldwildlife.org,wwf.org|WWF|nonprofit
Feeding America|feedingamerica.org||nonprofit
United Way|unitedway.org||nonprofit
AARP|aarp.org||nonprofit
Better Business Bureau|bbb.org|BBB|nonprofit
Consumer Reports|consumerreports.org||nonprofit
Charity Navigator|charitynavigator.org||nonprofit
Girl Scouts|girlscouts.org||nonprofit
Boy Scouts|scouting.org||nonprofit
YMCA|ymca.org||nonprofit
Planned Parenthood|plannedparenthood.org||nonprofit
ACLU|aclu.org||nonprofit
Electronic Frontier Foundation|eff.org|EFF|nonprofit
Amnesty International|amnesty.org||nonprofit
Oxfam|oxfam.org||nonprofit
Save the Children|savethechildren.org||nonprofit
Harvard University|harvard.edu||education
Stanford University|stanford.edu||education
MIT|mit.edu||education
Yale University|yale.edu||education
Princeton University|princeton.edu||education
Columbia University|columbia.edu||education
University of Pennsylvania|upenn.edu||education
University of California|berkeley.edu,ucla.edu,universityofcalifornia.edu||education
University of Michigan|umich.edu||education
University of Texas|utexas.edu||education
Ohio State University|osu.edu||education
Penn State|psu.edu||education
University of Florida|ufl.edu||education
Arizona State University|asu.edu||education
University of Phoenix|phoenix.edu||education
Southern New Hampshire University|snhu.edu||education
Western Governors University|wgu.edu||education
University of Oxford|ox.ac.uk||education
University of Cambridge|cam.ac.uk||education
University of Toronto|utoronto.ca||education
McGill University|mcgill.ca||education
University of Sydney|sydney.edu.au||education
"""

    private val chunks: List<String> get() = listOf(CHUNK_0, CHUNK_1)

    val brands: List<Brand> by lazy {
        chunks.asSequence().flatMap { it.lineSequence() }.mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 4 || p[0].isBlank() || p[1].isBlank()) return@mapNotNull null
            val name = p[0].trim()
            val aliases = (listOf(name) + p[2].split(',')).map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
            Brand(name, aliases, p[1].split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }, p[3].trim())
        }.toList()
    }
}
