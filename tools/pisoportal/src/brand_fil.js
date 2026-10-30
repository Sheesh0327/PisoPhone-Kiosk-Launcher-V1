(function(){
/* Filipino for a branded page (feature 049). The page itself only knows English; every text it writes is turned into Filipino here, as it is
   written, when the customer chose Filipino. A text that is not listed stays English. The wording is a draft the owner reads before release. */
var X={
"Getting your connection ready.":"Inihahanda ang iyong koneksyon.",
"Reload":"I-reload",
"Buy Wi-Fi time":"Bumili ng Wi-Fi time",
"Pick a plan, insert a coin and you are online.":"Pumili ng plano, magpasok ng barya, at online ka na.",
"Connected":"Nakakonekta",
"Almost out":"Malapit nang maubos",
"Paused":"Naka-pause",
"✓ You are online · time left":"✓ Online ka na · natitirang oras",
"✓ You are online":"✓ Online ka na",
"Paused, internet almost stopped · time left":"Naka-pause, halos huminto ang internet · natitirang oras",
"Less than 5 min left":"Wala pang 5 min ang natitira",
"Speed is reduced for a few minutes (fair use).":"Binabagalan ang bilis sa loob ng ilang minuto (patas na paggamit).",
"Resume time":"Ituloy ang oras",
"Resuming…":"Itinutuloy…",
"Pause time":"I-pause ang oras",
"Use now":"Gamitin ngayon",
"Get a voucher code":"Kumuha ng voucher code",
"You get a code to use later, within 30 days. You are not connected after paying.":"Makakakuha ka ng code na magagamit mamaya, sa loob ng 30 araw. Hindi ka makakakonekta pagkatapos magbayad.",
"Insert Coin":"Magpasok ng Barya",
"Add more time":"Dagdagan ang oras",
"Coins add up: every coin adds more time":"Nagsasama-sama ang mga barya: bawat barya ay dagdag na oras",
"Your voucher codes":"Ang iyong mga voucher code",
"Use":"Gamitin",
"Have a voucher?":"May voucher ka ba?",
"Redeem":"I-redeem",
"Send a file to the owner":"Magpadala ng file sa may-ari",
"Checking…":"Sinusuri…",
"Type the 10 characters of the code.":"I-type ang 10 character ng code.",
"Code filled in. Tap Redeem to add the time.":"Nailagay na ang code. Pindutin ang I-redeem para idagdag ang oras.",
"Code not accepted. Check the letters and try again.":"Hindi tinanggap ang code. Suriin ang mga letra at subukan ulit.",
"This code was already used.":"Nagamit na ang code na ito.",
"This code has expired.":"Nag-expire na ang code na ito.",
"You have time on the other plan. Use it up first.":"May oras ka pa sa kabilang plano. Ubusin muna iyon.",
"Could not save it. Try again.":"Hindi ma-save. Subukan ulit.",
"No pauses left":"Wala nang natitirang pause",
"No pauses left.":"Wala nang natitirang pause.",
"Pause needs 2 min left":"Kailangan ng 2 min na natitira para mag-pause",
"Finish adding coins first.":"Tapusin muna ang pagpasok ng barya.",
"Already paused.":"Naka-pause na.",
"There is no time to pause.":"Walang oras na ipa-pause.",
"Could not pause. Try again.":"Hindi ma-pause. Subukan ulit.",
"Getting the coin slot ready":"Inihahanda ang coin slot",
"Please wait, do not insert coins yet":"Maghintay muna, huwag pang magpasok ng barya",
"Insert coin now":"Magpasok na ng barya",
"Counting coins":"Binibilang ang mga barya",
"Each coin adds voucher time":"Bawat barya ay nagdaragdag ng voucher time",
"Each coin adds Wi-Fi time":"Bawat barya ay nagdaragdag ng Wi-Fi time",
"This pays for a voucher code. You are not connected yet.":"Ito ay bayad para sa voucher code. Hindi ka pa nakakonekta.",
"Coin received. Insert more, or tap Done.":"Natanggap ang barya. Magpasok pa, o pindutin ang Tapos na.",
"Page closed? Keep inserting, coins still count.":"Nagsara ang page? Ituloy ang pagpasok, bilang pa rin ang mga barya.",
"Done":"Tapos na",
"Closing…":"Isinasara…",
"Cancel":"Kanselahin",
"Your voucher code":"Ang iyong voucher code",
"Copy code":"Kopyahin ang code",
"Copied":"Nakopya",
"Press and hold the code to copy it":"Pindutin at hawakan ang code para kopyahin",
"Nothing is connected yet.":"Wala pang nakakonekta.",
" No time was added. To use the code: on the start page tap ":" Walang nadagdag na oras. Para gamitin ang code: sa unang page, pindutin ang ",
", type or paste the code and tap Redeem. It works once, until ":", i-type o i-paste ang code, at pindutin ang I-redeem. Isang beses lang magagamit, hanggang ",
"Save voucher file":"I-save ang voucher file",
"Saved. Save it again":"Na-save. I-save ulit",
"Could not save. Take a screenshot":"Hindi ma-save. Kunan ng screenshot",
"Tip: take a screenshot of this code.":"Tip: kunan ng screenshot ang code na ito.",
"Continue browsing":"Magpatuloy sa pag-browse",
"Back to start":"Bumalik sa simula",
"Payment received.":"Natanggap na ang bayad.",
" Connecting you…":" Kinokonekta ka…",
" Not online yet: turn Wi-Fi off and on. Your time is saved.":" Hindi ka pa online: i-off at i-on ang Wi-Fi. Naka-save ang iyong oras.",
"Try again":"Subukan ulit",
"No coins detected, you were not charged.":"Walang nakitang barya, hindi ka siningil.",
"Another customer is paying. Try again in a few seconds.":"May ibang customer na nagbabayad. Subukan ulit sa loob ng ilang segundo.",
"This phone's browser cannot run the coin page.":"Hindi mapatakbo ng browser ng phone na ito ang coin page.",
"Connection lost. Reconnecting…":"Nawala ang koneksyon. Kumokonekta ulit…",
"Still connecting. Check you are on the TechNet Wi-Fi.":"Kumokonekta pa rin. Tiyaking nasa Wi-Fi ng shop ka.",
"Still connecting. Check you are on the shop's Wi-Fi.":"Kumokonekta pa rin. Tiyaking nasa Wi-Fi ng shop ka.",
"House rules":"Mga patakaran"
};
var P="(Endurance|HyperSpeed)";
var R=[
[/^Full speed · slows after (\d+) GB$/,"Buong bilis · bumabagal pagkatapos ng $1 GB"],
[/^Up to (\d+)\/(\d+) Mbps · much more time per peso$/,"Hanggang $1/$2 Mbps · mas mahabang oras kada piso"],
[/^Insert within (\d+) s$/,"Magpasok sa loob ng $1 s"],
[/^(\d+) MB used$/,"$1 MB ang nagamit"],
[/^Use by (.*)$/,"Gamitin bago ang $1"],
[/^Time is on hold\. (?:Resume by (.*)|Resume soon); time not resumed by then is lost\.$/,function(m,a){return"Naka-hold ang oras. "+(a?"Ituloy bago ang "+a:"Ituloy agad")+"; mawawala ang oras na hindi naituloy."}],
[/^You can pause again in (\d+) s(\.?)$/,"Maaari kang mag-pause ulit sa loob ng $1 s$2"],
[/^Pay ₱(\d+) more to unlock a pause$/,"Magbayad pa ng ₱$1 para ma-unlock ang pause"],
[/^(\d+) pauses? left$/,"$1 pause ang natitira"],
[/^Pause needs (\d+) min left(\.?)$/,"Kailangan ng $1 min na natitira para mag-pause$2"],
[/^Pause unlocks after paying ₱(\d+) more\.$/,"Mag-a-unlock ang pause pagkatapos magbayad ng ₱$1 pa."],
[/^Too many empty tries\. Wait about (\d+) s\.$/,"Masyadong maraming subok na walang barya. Maghintay ng mga $1 s."],
[/^Another customer is paying\. Try again in about (\d+) s\.$/,"May ibang customer na nagbabayad. Subukan ulit sa loob ng mga $1 s."],
[/^Too many tries\. Try again in (.+)\.$/,"Masyadong maraming subok. Subukan ulit sa loob ng $1."],
[/^Added (.+)\. Enjoy!$/,"Nadagdag ang $1. Mag-enjoy!"],
[/^= (.+) of Wi-Fi$/,"= $1 ng Wi-Fi"],
[/^time left · (₱\d+ = .+) bought$/,"natitirang oras · $1 ang nabili"],
[new RegExp("^You still have (.+) of "+P+" time\\. Add more \\2 time, or switch to "+P+" and give up the time you have left\\.$"),"May natitira ka pang $1 ng $2 time. Magdagdag pa ng $2 time, o lumipat sa $3 at isuko ang natitirang oras mo."],
[new RegExp("^Add "+P+" time$"),"Magdagdag ng $1 time"],
[new RegExp("^Switch to "+P+" · lose my time$"),"Lumipat sa $1 · isuko ang oras ko"],
[/^Coin payment is not available right now\. Try again, or ask the attendant \((.*)\)\.$/,"Hindi available ang bayad gamit ang barya ngayon. Subukan ulit, o magtanong sa attendant ($1)."]
];
var L="en",btn=document.getElementsByClassName("lang")[0];
function tr(t){var s=t.replace(/(\d+) hrs?\b/g,"$1 oras"),i;if(X.hasOwnProperty(s))return X[s];for(i=0;i<R.length;i++)if(R[i][0].test(s))return s.replace(R[i][0],R[i][1]);return s}
function one(n){var v=n.nodeValue,r;if(!v||!/\S/.test(v))return;r=tr(v);if(r!==v){n.__o=v;n.nodeValue=r}}
function back(n){if(n.__o!==undefined){n.nodeValue=n.__o;n.__o=undefined}}
function walk(root,f){var w=document.createTreeWalker(root,4,null,false),n,l=[];while((n=w.nextNode()))l.push(n);for(n=0;n<l.length;n++)f(l[n])}
function sub(n){if(n.nodeType===3)one(n);else if(n.nodeType===1)walk(n,one)}
try{new MutationObserver(function(ms){var i,j,m;if(L!=="fil")return;for(i=0;i<ms.length;i++){m=ms[i];if(m.type==="characterData")one(m.target);else for(j=0;j<m.addedNodes.length;j++)sub(m.addedNodes[j])}}).observe(document.body,{childList:true,characterData:true,subtree:true})}catch(e){}
function setL(l,save){var e=document.querySelectorAll("[data-f]"),i,h;L=l;
 document.documentElement.lang=l==="fil"?"fil":"en";
 for(i=0;i<e.length;i++){h=e[i].getAttribute(l==="fil"?"data-f":"data-e");e[i].innerHTML=h;e[i].hidden=!h}
 walk(document.body,l==="fil"?one:back);
 if(btn){btn.textContent=l==="fil"?"English":"Filipino";btn.setAttribute("aria-label",l==="fil"?"Palitan ang wika sa English":"Change language to Filipino")}
 if(save)try{localStorage.setItem("pp-lang",l)}catch(x){}}
if(btn)btn.onclick=function(){setL(L==="fil"?"en":"fil",1)};
var s=null;try{s=localStorage.getItem("pp-lang")}catch(x){}
if(s==="fil")setL("fil",0)
})();
