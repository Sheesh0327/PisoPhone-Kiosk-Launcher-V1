// TechNet PisoHub console: the page's behaviour. PISO_UI_VERSION rises with every change that is published (scripts/sign_console_ui.py).
window.PISO_UI_VERSION = 13;
(function(){
var $=function(i){return document.getElementById(i)},tab='over',timer=null,last={},sales=null,TABS=['over','cli','ph','sales','vch','set'];
if($('t-brand'))TABS.push('brand'); /* (a newer script on an older console page, which has no Branding screen, still starts) */
function esc(t){return String(t).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]})}
function api(path,body){var o=body===undefined?{}:{method:'POST',headers:{'X-Piso':'1','Content-Type':'application/json'},body:JSON.stringify(body)};
 return fetch(path,o).then(function(r){if(r.status===401&&path!=='/api/login'){showLogin();throw new Error('login')}return r.json().then(function(j){j._code=r.status;return j})})}
function mb(kb){kb=+kb||0;return kb>=1048576?(kb/1048576).toFixed(1)+' GB':kb>=1024?(kb/1024).toFixed(1)+' MB':kb+' kB'}
function sp(k){k=+k||0;return k>=1000?(k/1000).toFixed(1)+' Mbps':k+' kbps'}
function tm(s){s=+s||0;if(s<=0)return '-';var h=Math.floor(s/3600),m=Math.floor(s%3600/60);return h?h+'h '+m+'m':m+' min'}
function mins(m){m=+m||0;return m<60?m+' min':m%60?Math.floor(m/60)+' h '+m%60+' min':m/60+(m===60?' hour':' hours')}
function peso(n){return '₱'+(+n||0).toLocaleString()}
function css(v){return getComputedStyle(document.documentElement).getPropertyValue(v).trim()||'#1d63d6'}
function dt(s){return s?new Date(s*1e3).toLocaleDateString([],{day:'numeric',month:'short',year:'numeric'}):'never'}
function showLogin(){clearInterval(timer);$('app').className='hide';$('login').className='';$('pw').focus()}
function showApp(){$('login').className='hide';$('app').className='';$('adv').href='http://'+location.hostname+(location.port==='8081'?'/':':8080/');refresh();clearInterval(timer);timer=setInterval(refresh,4000)}
$('gobtn').onclick=function(){api('/api/login',{password:$('pw').value}).then(function(j){if(j.ok){$('pw').value='';$('err').textContent='';showApp()}else $('err').textContent=j.error==='LOCKED'?'Too many tries. Wait '+j.retry+' s.':'Wrong password.'}).catch(function(){$('err').textContent='The router did not answer.'})};
$('pw').onkeydown=function(e){if(e.key==='Enter')$('gobtn').onclick()};
$('out').onclick=function(){api('/api/logout',{}).then(showLogin)};
$('th').onclick=function(){var r=document.documentElement,d=(r.dataset.theme||(matchMedia('(prefers-color-scheme:dark)').matches?'dark':'light'))==='dark';r.dataset.theme=d?'light':'dark';try{localStorage.setItem('piso_theme',r.dataset.theme)}catch(e){}if(last.over)draw(last.over.history);boxTheme()};
try{var T=localStorage.getItem('piso_theme');if(T)document.documentElement.dataset.theme=T}catch(e){}
document.querySelectorAll('#tabs button').forEach(function(b){b.onclick=function(){tab=b.dataset.t;document.querySelectorAll('#tabs button').forEach(function(x){x.className=x===b?'on':''});TABS.forEach(function(t){$('t-'+t).className=t===tab?'':'hide'});refresh()}});
/* a confirmation that names what it does; Escape closes it and focus goes back to the button that opened it */
function ask(title,text,verb,danger,go){var d=$('dlg'),from=document.activeElement;$('dt').textContent=title;$('dp').textContent=text;var y=$('dyes');y.textContent=verb;y.className='go';if(danger)y.style.background='var(--bad)';else y.style.background='';
 $('dno').onclick=function(){d.close()};y.onclick=function(){d.close();go()};d.onclose=function(){if(from&&from.focus)from.focus()};if(d.showModal)d.showModal();else if(confirm(title+'\n'+text))go()}
var ICO={'Customers online':'<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6M16 4.5a3.5 3.5 0 0 1 0 7M18 14.5c2.2.6 3.5 2.6 3.5 5.5"/>','Internet':'<path d="M3 12h4l3-8 4 16 3-8h4"/>','Coin box':'<ellipse cx="12" cy="6" rx="8" ry="3"/><path d="M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/>','Records':'<path d="M6 3h9l4 4v14H6zM14 3v5h5M9 14l2 2 4-4"/>','Sales detail':'<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/>','Router':'<rect x="3" y="14" width="18" height="6" rx="2"/><path d="M7 17h.01M11 17h.01M8 10a6 6 0 0 1 8 0M5.5 7.5a10 10 0 0 1 13 0"/>','Alerts':'<path d="M12 3l10 18H2zM12 10v5M12 18h.01"/>','Total':'<path d="M4 20V10M10 20V4M16 20v-6M2 20h20"/>','Coins':'<circle cx="12" cy="12" r="8"/><path d="M9.5 9.5h4a1.5 1.5 0 0 1 0 3h-3a1.5 1.5 0 0 0 0 3h4M12 7.5v1.5M12 15.5V17"/>','Voucher codes':'<path d="M3 9V6a1 1 0 0 1 1-1h16a1 1 0 0 1 1 1v3a2.5 2.5 0 0 0 0 5v3a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-3a2.5 2.5 0 0 0 0-5z"/><path d="M14 5v14" stroke-dasharray="2 3"/>','Old printed slips':'<path d="M6 3h9l4 4v14H6zM14 3v5h5M9 13h6M9 17h6"/>'};
function ico(k){return ICO[k]?'<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'+ICO[k]+'</svg>':''}
function tile(k,v,s,cls){return '<div class="card"><div class="k">'+ico(k)+esc(k)+'</div><div class="v"'+(cls?' style="color:var(--'+cls+')"':'')+'>'+v+'</div><div class="s">'+(s||'')+'</div></div>'}
function chip(t,c){return '<span class="chip '+(c||'')+'">'+esc(t)+'</span>'}
function draw(h){var c=$('cv'),g=c.getContext('2d'),w=c.width,ht=c.height;g.clearRect(0,0,w,ht);if(!h||h.length<2){return}
 var mx=1,col=[css('--bfg'),css('--teal')];h.forEach(function(p){mx=Math.max(mx,p[1],p[2])});
 [1,2].forEach(function(l){g.beginPath();h.forEach(function(p,i){var x=i*(w/(h.length-1)),y=ht-4-(ht-12)*p[l]/mx;i?g.lineTo(x,y):g.moveTo(x,y)});g.strokeStyle=col[l-1];g.lineWidth=3;g.stroke()});
 $('cvl').innerHTML='<span style="color:'+col[0]+'">&#9632;</span> download &nbsp; <span style="color:'+col[1]+'">&#9632;</span> upload &nbsp; last ~'+Math.round((h[h.length-1][0]-h[0][0])/60)+' min, peak '+sp(mx)}
/* the money line: the coin box's count against the sales record, both numbers, and why they may differ (feature 036) */
function money(m){var e=$('mny');if(!m){e.className='msg hide';return}var l=peso(m.ledger),b=peso(m.box),d=peso(m.diff),c='',t='';
 if(m.state==='agrees'){c='ok';t='<b>Money agrees.</b> Coin box counted '+b+', sales record '+l+'.'}
 else if(m.state==='box_ahead'){c='';t='Coin box counted '+b+', sales record '+l+'. <b>Difference '+d+'</b>: coins counted but not yet in the record. Normal for a minute while a customer is paying; if it stays, tell your installer.'}
 else if(m.state==='box_ahead_phones'){c='ok';t='Coin box counted '+b+' (includes rental-phone coins), sales record '+l+'. Difference '+d+' = rental phones. <b>Normal.</b>'}
 else if(m.state==='mismatch'){c='bad';t='<b>MISMATCH.</b> Sales record '+l+', coin box counted '+b+'. The record has '+d+' the box did not count.'}
 else if(m.state==='box_silent'){c='';t='<b>Coin box is not answering.</b> Sales record '+l+'. The comparison resumes when the box is back.'}
 else if(m.state==='bad_ledger'){c='bad';t='<b>The sales record was changed:</b> line '+m.bad_line+' is wrong.'}
 else{c='n';t='<b>No sales recorded yet.</b> Coin box counted '+b+'.'}
 if(m.restarted&&m.state!=='bad_ledger'&&m.state!=='box_silent')t+=" The box's count restarted when its revenue was collected; compared from here.";
 e.className='msg '+c;e.innerHTML=t}
/* the update line: is the router, is the coin box up to date, or waiting for an update (feature 044). Gold only for a wait longer than a day or a failure. */
function upd(v,now){var e=$('upd');if(!v){e.className='msg hide';return}var gold=false,out=[];
 function one(n,r,cmd){if(!r){out.push(n+': not checked yet.');return}var l=esc(r.latest),run=esc(r.running),d=esc(r.detail),old=now-r.since>=86400,days=Math.max(1,Math.floor((now-r.since)/86400)),w=days+(days===1?' day':' days');
  if(r.state==='current')out.push(n+' '+run+' is up to date.');
  else if(r.state==='available')out.push(n+' '+l+' is available.');
  else if(r.state==='waiting'){if(old){gold=true;out.push(n+' '+l+' has waited '+w+' for a quiet moment. Install it when you close: '+cmd+'.')}else out.push(n+' '+l+' is waiting for a quiet moment.')}
  else if(r.state==='off'){if(old)gold=true;out.push(n+' '+l+' is available. Automatic updates are off: run '+cmd+'.')}
  else if(r.state==='rollout')out.push(n+' '+l+" is rolling out in stages; this router's turn has not come.");
  else if(r.state==='newer')out.push(n+' '+run+' is newer than the published '+l+'.');
  else if(r.state==='failed'){gold=true;out.push(n+(l?' '+l:'')+' could not be installed: '+d+'. Run '+cmd+' to try by hand.')}
  else out.push(n+': not checked: '+d+'.')}
 one('Router',v.router,'piso-setup self-update');one('Coin box',v.box,'piso-setup box-update');
 var age=v.checked?Math.floor((now-v.checked)/3600):0;if(age>=3)out.push('(checked '+age+' hours ago)');
 e.className='msg '+(gold?'gold':'n');e.innerHTML=out.join(' ')}
function over(j){last.over=j;upd(j.versions,j.now);money(j.money);var m=j.week||[],wk=m.reduce(function(a,b){return a+b},0);
 $('hm').textContent=peso(j.today);$('hs').textContent='This week '+peso(wk)+' · all time '+peso(j.total);
 $('tiles').innerHTML=tile('Customers online',j.online,j.waiting+' waiting to pay')+tile('Internet',sp(j.rx_kbps),'down; up '+sp(j.tx_kbps))+
  tile('Coin box',j.box_ok?'OK':'Offline',j.box_ok?(j.box_signed?'answering, answers signed':'answering. Update its firmware: answers are not signed yet'):'check power and Wi-Fi',j.box_ok?'ok':'bad')+tile('Records',j.ledger_ok?'OK':'Check',j.ledger_lines+' lines'+(j.ledger_ok?'':' (line '+j.ledger_lines+' is wrong)'),j.ledger_ok?'ok':'bad')+
  (j.ledger_kept!==undefined?tile('Sales detail',j.ledger_kept+(j.ledger_kept===1?' month':' months'),j.ledger_pruned?(j.ledger_pruned+(j.ledger_pruned===1?' older month was':' older months were')+' deleted to save space. Your total ('+peso(j.total)+') and each deleted month\'s total are kept.'+((j.ledger_kept-1<j.ledger_wanted)?' The space allows fewer than the '+j.ledger_wanted+' closed months you asked for (Settings).':'')):'nothing deleted'):'')+
  tile('Router',Math.floor(j.uptime/3600)+' h','up; '+Math.round(j.mem_kb/1024)+' MB memory free')+
  ((j.alerts&&j.alerts.length)?tile('Alerts',j.alerts.length,j.alerts.map(esc).join('<br>'),'gold'):'');draw(j.history)}
function cli(j){var a=j.clients||[];$('cl').innerHTML=a.map(function(c){var on=c.state==='Authenticated',nm=c.name||c.mac;
 return '<tr><td><b>'+esc(nm)+'</b><div class="s">'+esc(c.mac)+'</div></td><td class="hm">'+esc(c.ip)+'</td><td>'+(on?(c.paused?chip('paused','gold'):chip(c.plan||'online',c.plan==='endurance'?'gold':'ok')):chip('waiting'))+'</td><td class="n">'+tm(c.left)+'</td><td class="n hm">'+mb(c.dl_kb+c.ul_kb)+'</td><td class="n hm">'+(on?'&darr; '+sp(c.dl_kbps)+' &uarr; '+sp(c.ul_kbps):'-')+'</td><td>'+(on?'<button class="b" data-mac="'+esc(c.mac)+'" data-nm="'+esc(nm)+'">Disconnect</button>':'')+'</td></tr>'}).join('')||'<tr><td colspan="7" class="empty">Nobody is connected right now.<div class="s">Customers show up here as soon as they join the Wi-Fi.</div></td></tr>';
 if($('clc'))$('clc').textContent=a.length?a.filter(function(c){return c.state==='Authenticated'}).length+' online, '+a.filter(function(c){return c.state!=='Authenticated'}).length+' waiting':'';
 $('clnote').textContent='Updates every few seconds. A disconnected customer keeps the paid time and can connect again.';
 document.querySelectorAll('#cl button.b').forEach(function(b){b.onclick=function(){ask('Disconnect '+b.dataset.nm+'?','Their paid time stays; they can reconnect.','Disconnect '+b.dataset.nm,true,function(){api('/api/kick',{mac:b.dataset.mac}).then(refresh)})}})}
/* rental phones: the coin box's own page, passed through by the router one port above the console; it follows this page's light or dark */
function theme(){var r=document.documentElement;return r.dataset.theme||(matchMedia('(prefers-color-scheme:dark)').matches?'dark':'light')}
function boxOrigin(){return 'http://'+location.hostname+':'+((+location.port||80)+1)}
function boxTheme(){var f=$('boxf');if(f&&f.contentWindow&&f.src)try{f.contentWindow.postMessage({piso:'theme',theme:theme()},boxOrigin())}catch(e){}}
function ph(){var f=$('boxf');if(!f.getAttribute('src')){f.onload=boxTheme;f.src=boxOrigin()+'/?embed=1&theme='+theme()}$('boxdirect').href=boxOrigin()+'/'}
/* sales */
function soldOut(j){if(j&&j.error==='NOT_FOUND'){$('t-'+tab).insertAdjacentHTML('afterbegin','<p class="msg">This router is on an older version. Run <b>piso-setup update</b> to use this page.</p>');return true}return false}
/* kinds: coin = coins that bought time at once, vcoin = coins that bought a voucher code (income when the coins were counted), voucher = printed slips of older releases */
function tot(x){return (x.coin||0)+(x.vcoin||0)+(x.voucher||0)}
function sale(j){if(soldOut(j))return;sales=j;var d=j.days||[],mx=1,k=j.kinds||{},co=k.coin||0,vc=k.vcoin||0,vo=k.voucher||0,vi=k.vi||0,vv=j.vouchers||{redeemed:0,waiting:0},n=d.reduce(function(a,x){return a+x.n},0),best=null;
 d.forEach(function(x){var t=tot(x);if(t>mx)mx=t;if(!best||t>best.t)best={d:x.d,t:t}});
 $('st').innerHTML=tile('Total',peso(co+vc+vo),n+' payments','gold')+tile('Coins',peso(co+vc),'HyperSpeed '+peso(j.plans.hyper)+' · Endurance '+peso(j.plans.endurance))+
  tile('Voucher codes',vi,'issued · '+peso(vc)+' of the coins · '+vv.redeemed+' redeemed · '+vv.waiting+' waiting')+(vo?tile('Old printed slips',peso(vo),'booked when used'):'');
 $('vnote').textContent='A voucher code bought with coins is income when the coins are counted. Using the code later adds no income.';
 $('sb').innerHTML=d.map(function(x){var t=tot(x);return '<div title="'+esc(x.d)+': '+peso(t)+'" style="height:'+Math.max(2,Math.round(t/mx*104))+'px">'+(x===d[d.length-1]&&t?'<span>'+peso(t)+'</span>':'')+'</div>'}).join('');
 var per=Math.max(4,Math.floor(($('sd').clientWidth||320)/46)),step=Math.max(1,Math.ceil(d.length/per));
 $('sd').innerHTML=d.map(function(x,i){return '<div>'+(i%step===0?x.d.slice(5).replace('-','/'):'')+'</div>'}).join('');
 $('ssum').textContent=best&&best.t?'Best day: '+best.d+', '+peso(best.t)+'.':'No sales in this period.';
 $('shd').innerHTML='<tr><th>Day</th><th class="n">Use now</th><th class="n">Voucher codes</th>'+(vo?'<th class="n">Old slips</th>':'')+'<th class="n">Total</th><th class="n hm">Payments</th></tr>';
 $('sl').innerHTML=d.slice().reverse().map(function(x){return '<tr><td>'+esc(x.d)+'</td><td class="n">'+peso(x.coin)+'</td><td class="n">'+peso(x.vcoin||0)+(x.vi?' <span class="s">('+x.vi+')</span>':'')+'</td>'+(vo?'<td class="n">'+peso(x.voucher)+'</td>':'')+'<td class="n"><b>'+peso(tot(x))+'</b></td><td class="n hm">'+x.n+'</td></tr>'}).join('')}
$('rng').onchange=refresh;
$('csv').onclick=function(){if(!sales)return;var t='day,use_now,voucher_codes,vouchers_issued,old_slips,total,payments\n'+sales.days.map(function(x){return [x.d,x.coin,x.vcoin||0,x.vi||0,x.voucher,tot(x),x.n].join(',')}).join('\n')+'\n',a=document.createElement('a');a.href=URL.createObjectURL(new Blob([t],{type:'text/csv'}));a.download='sales-'+$('rng').value+'-days.csv';a.click()};
/* vouchers: the list of what was issued (no codes), and cancelling an unused one */
function vch(j){if(soldOut(j))return;var a=j.batches||[];$('vl2').innerHTML=a.slice().reverse().map(function(b){var left=b.count-b.used,gone=b.expires&&b.expires*1e3<Date.now();
 return '<tr><td><b>'+(b.bought===false?esc(b.label||'Printed slips'):'Bought with coins')+'</b><div class="s">'+dt(b.created)+(b.count>1?' · '+b.count+' codes':'')+' · '+(b.plan==='endurance'?'Endurance':'HyperSpeed')+'</div></td><td>'+mins(b.minutes)+' · '+peso(b.pesos)+'</td><td>'+(b.void?chip('Cancelled','bad'):!left?chip('Used','ok'):b.used?chip(b.used+' of '+b.count+' used'):chip('Not used','gold'))+'</td><td>'+(gone?chip('Expired','bad'):b.expires?dt(b.expires):'No expiry')+'</td><td>'+(b.void||!left||gone?'':'<button class="b dg" data-v="'+b.id+'" data-n="'+esc(b.bought===false?(b.label||b.id):dt(b.created))+'" data-l="'+left+'">Cancel</button>')+'</td></tr>'}).join('')||'<tr><td colspan="5" class="empty">No vouchers yet.<div class="s">Customers get one when they choose "Get a voucher code" at payment.</div></td></tr>';
 document.querySelectorAll('#vl2 button[data-v]').forEach(function(b){b.onclick=function(){ask('Cancel this voucher ('+b.dataset.n+')?',b.dataset.l+' unused code'+(b.dataset.l==='1'?'':'s')+' will stop working. The customer already paid; the money stays in the sales.','Cancel the voucher',true,function(){api('/api/vouchers/void',{batch:b.dataset.v}).then(refresh)})}})}
/* settings */
function sets(j){if(soldOut(j))return;sqm(j);if(document.activeElement!==$('vcs'))$('vcs').value=j.voucher_choice?'on':'off';if(document.activeElement!==$('fss')&&j.file_send!==undefined)$('fss').value=j.file_send?'on':'off';if(document.activeElement!==$('lkb')&&j.ledger_max_kb!==undefined)$('lkb').value=j.ledger_max_kb;if(document.activeElement!==$('lkm')&&j.ledger_keep_months!==undefined)$('lkm').value=j.ledger_keep_months;if(j.file_send_key!==undefined)$('fsk').textContent=j.file_send_key?(j.file_send?'Customers can send files now.':''):'The print server key is not installed on this router, so the button stays hidden even when this is On (see the manual: Sending a file to the owner).'}
$('fss').onchange=function(){var s=$('fss');$('setm').textContent='';api('/api/settings',{file_send:s.value}).then(function(j){$('setm').textContent=j.ok?'Saved. Open customer pages follow at once.':'Could not save.';if(j.ok){s.value=j.file_send?'on':'off';sets(j)}}).catch(function(){})};
$('lkb').onchange=function(){var m=Math.round(+$('lkb').value);if(!(m===0||(m>=256&&m<=16384))){$('setm').textContent='Use 0, or a number from 256 to 16384.';return}$('setm').textContent='';api('/api/settings',{ledger_max_kb:String(m)}).then(function(j){$('setm').textContent=j.ok?'Saved. Old months are deleted when the next month starts.':(j.why||'Could not save.');if(j.ok)sets(j)}).catch(function(){})};
$('lkm').onchange=function(){var m=Math.round(+$('lkm').value);if(!(m>=1&&m<=60)){$('setm').textContent='Use a number from 1 to 60.';return}$('setm').textContent='';api('/api/settings',{ledger_keep_months:String(m)}).then(function(j){$('setm').textContent=j.ok?'Saved.':(j.why||'Could not save.');if(j.ok)sets(j)}).catch(function(){})};
$('vcs').onchange=function(){var s=$('vcs');$('setm').textContent='';api('/api/settings',{voucher_choice:s.value}).then(function(j){$('setm').textContent=j.ok?'Saved. Open customer pages follow at once.':'Could not save.';if(j.ok)s.value=j.voucher_choice?'on':'off'}).catch(function(){})};
/* smooth line (SQM) */
var sqT=null,sqSeen='',sqDirty=false;
function sqsay(t,bad){var m=$('sqm2');m.textContent=t||'';m.className=bad?'msg bad':'s'}
function sqnum(v){v=String(v).trim();return /^\d+(\.\d)?$/.test(v)&&+v>=0.1&&+v<=10000?+v:null}
function sqlim(v,p){return Math.floor(v*10*p+1e-6)/1000}
function sqlive(){var d=sqnum($('sqd').value),u=sqnum($('squ').value),p=Math.round(+$('sqp').value);$('sql').textContent=d!==null&&u!==null&&p>=50&&p<=100?'Will limit to '+sqlim(d,p)+' Mbps down and '+sqlim(u,p)+' Mbps up ('+p+'% of the line).':'Line speed not set; smoothing is off.'}
function sqm(j){var q=j&&j.sqm;if(!q||!$('sqc'))return;var t=q.speedtest,i=q.install,off=!q.available,s='',n;
 $('sqc').className='card'+(off?' off':'');['sqd','squ','sqp','sqs','sqk'].forEach(function(x){$(x).disabled=off});
 if(!sqDirty&&!$('sqc').contains(document.activeElement)){$('sqd').value=q.down;$('squ').value=q.up;$('sqp').value=q.percent;$('sqs').value=q.on?'on':'off'}
 $('sqi').className='b'+(off?'':' hide');$('sqi').disabled=!i.can||q.installing;$('sqt').disabled=!t.can_start;
 if(off)n=q.reason+(q.installing?' Installing now: this takes about a minute.':i.can?' You can try installing it now.':' '+i.why);
 else if(q.applying)n='Applying...';else if(q.on&&q.active)n='Smoothing is on: the line is limited to '+q.limit_down/1000+' Mbps down and '+q.limit_up/1000+' Mbps up ('+q.qdisc+').';
 else if(q.on)n=q.reason||'Smoothing is switched on but not working yet.';else n='Smoothing is off. Customers notice nothing.';
 $('sqn').textContent=n;
 if(t.state==='running')s='Testing your line. This takes about a minute; please keep the Wi-Fi quiet meanwhile.';
 else if(t.state==='done'&&t.cpu_limited)s='This router may be the limit: with its processor at '+t.cpu+'% it measured '+t.down+' Mbps down and '+t.up+' Mbps up. Confirm with a speed test on a phone before you type these in.';
 else if(t.state==='done'){var g=t.down+'/'+t.up;if(t.age<900&&g!==sqSeen){sqSeen=g;sqDirty=true;$('sqd').value=t.down;$('squ').value=t.up}s='Measured '+t.down+' Mbps down and '+t.up+' Mbps up. The boxes above are filled in: check them, then press Save.'}
 else if(t.state==='failed')s='The test did not finish: '+t.reason;else if(!off&&t.blocked)s=t.blocked;
 $('sqb').textContent=s;sqlive();
 if(q.applying||q.installing||t.state==='running'){clearTimeout(sqT);sqT=setTimeout(function(){if(tab==='set')api('/api/settings').then(sets).catch(function(){})},1500)}}
if($('sqc')){['sqd','squ','sqp'].forEach(function(x){$(x).oninput=function(){sqDirty=true;sqlive()}});$('sqs').onchange=function(){sqDirty=true};
$('sqk').onclick=function(){var d=sqnum($('sqd').value),u=sqnum($('squ').value),p=Math.round(+$('sqp').value),on=$('sqs').value,b;
 if(($('sqd').value&&d===null)||($('squ').value&&u===null)){sqsay('Speeds are numbers from 0.1 to 10000, with at most one decimal.',1);return}
 if(!(p>=50&&p<=100)){sqsay('Leave a room of 50 to 100 percent.',1);return}
 if(on==='on'&&(d===null||u===null)){sqsay('Type both speeds before you switch it on.',1);return}
 b={sqm_on:on,sqm_percent:String(p)};if(d!==null)b.sqm_down=String(d);if(u!==null)b.sqm_up=String(u);sqsay('');
 api('/api/settings',b).then(function(j){if(j.ok){sqDirty=false;sqsay('Saved. The router is applying it now.');sets(j)}else sqsay(j.why||'Could not save.',1)}).catch(function(){})};
$('sqt').onclick=function(){sqsay('');api('/api/speedtest',{}).then(function(j){if(j.ok)sets(j);else sqsay(j.why||'Could not start the test.',1)}).catch(function(){})};
$('sqi').onclick=function(){sqsay('');api('/api/sqm/install',{}).then(function(j){if(j.ok)sets(j);else sqsay(j.why||'Could not start the install.',1)}).catch(function(){})};}
/* prices: the editor keeps what is typed until it is saved (the page refreshes every few seconds and must not overwrite it) */
var pdirty=false;
function prow(p,m){return '<div class="tr"><span class="s">&#8369;</span><input type="number" class="tp" min="1" max="1000" step="1" value="'+(+p||'')+'" aria-label="Pesos"><span class="s">buys</span><input type="number" class="tm" min="1" max="10080" step="1" value="'+(+m||'')+'" aria-label="Minutes"><span class="s tx">'+(m>=60?mins(m):'min')+'</span><button class="b rm" aria-label="Remove this line">Remove</button></div>'}
function pfill(id,list){$(id).innerHTML=(list||[]).map(function(t){return prow(t[0],t[1])}).join('')}
function mbps(k){return +((+k||0)/1000).toFixed(3)}
function prices(j,force){if(!j||j.error)return;$('prn').textContent=j.changed?'Changed in the console':'The settings file\'s prices';if(!force&&(pdirty||$('prc').contains(document.activeElement)))return;
 pfill('rows-h',j.hyper);pfill('rows-e',j.endurance);$('pro').value=j.prorata;$('edn').value=mbps(j.down);$('eup').value=mbps(j.up)}
function pcollect(id){var out=[];document.querySelectorAll('#'+id+' .tr').forEach(function(r){var p=r.querySelector('.tp').value.trim(),m=r.querySelector('.tm').value.trim();if(p===''&&m==='')return;out.push(p+':'+m)});return out.join(' ')}
function pmsg(t,bad){var e=$('prm');e.textContent=t;e.style.color=bad?'':'var(--mut)'}
$('prc').addEventListener('input',function(e){pdirty=true;pmsg('');if(e.target.classList.contains('tm')){var m=+e.target.value;e.target.parentNode.querySelector('.tx').textContent=m>=60?mins(m):'min'}});
$('prc').addEventListener('click',function(e){var b=e.target;if(b.classList&&b.classList.contains('rm')){b.parentNode.remove();pdirty=true;pmsg('')}});
$('add-h').onclick=function(){$('rows-h').insertAdjacentHTML('beforeend',prow('',''));pdirty=true};
$('add-e').onclick=function(){$('rows-e').insertAdjacentHTML('beforeend',prow('',''));pdirty=true};
$('prs').onclick=function(){pmsg('');var body={hyper:pcollect('rows-h'),endurance:pcollect('rows-e'),prorata:String(Math.round(+$('pro').value||0)),down:String(Math.round((+$('edn').value||0)*1000)),up:String(Math.round((+$('eup').value||0)*1000))};
 api('/api/pricing',body).then(function(j){if(j.ok){pdirty=false;prices(j,true);pmsg('Saved. New payments use these prices.')}else pmsg(j.why?'Not saved: '+j.why+'.':'Not saved.',true)}).catch(function(){})};
$('prr').onclick=function(){ask('Go back to the settings file?','The prices you changed here are forgotten; the router\'s settings file applies again.','Go back',true,function(){api('/api/pricing',{reset:'1'}).then(function(j){pdirty=false;if(j.ok){prices(j,true);pmsg('Done. The settings file\'s prices apply.')}}).catch(function(){})})};
/* Branding (feature 049): a draft the owner edits, a preview of it, and Publish. The draft is saved on the router as it is typed; customers see
   nothing until Publish. */
var bm=null,bst=null,blang='en',bsave=null,bbusy=false,bload=false;
var BNAME={technet:'TechNet',dagat:'Dagat (sea)',tropiko:'Tropiko',ginto:'Ginto (gold)',rosas:'Rosas (rose)',berde:'Berde (green)'};
var BLAY={classic:['Classic','A bar with your name across the top. The standard layout.'],banner:['Banner','Your logo and name large and centred at the top.'],compact:['Compact','Smaller, with the house rules folded away: more fits on the screen.'],poster:['Poster','A big heading in your colour; the coin button always stays in reach.']};
var BKEYS=['name','palette','accent','layout'];
function bmsg(t,bad){var e=$('bmsg');e.textContent=t||'';e.style.color=bad?'var(--bad)':'var(--mut)'}
function bform(){var o={};BKEYS.forEach(function(k){o[k]=bm[k]||''});['tagline','welcome','contact','rules','hours','promo'].forEach(function(f){['en','fil'].forEach(function(l){o[f+'_'+l]=bm[f+'_'+l]||''})});return o}
function bprev(){$('bprev').src='/brand/preview?v='+Date.now()}
function bcount(k,n,max){var e=$('c-'+k);if(e)e.textContent=n+' of '+max+' characters'}
function bacc(){var p=bst.palettes.filter(function(x){return x.id===(bm.palette||'technet')})[0];return !bm.palette||bm.palette==='technet'?'#1d63d6':p?p.accent:'#1d63d6'}
function bshow(){var L=bst.limits;$('bn').value=bm.name||'';bcount('name',Array.from(bm.name||'').length,L.name);
 document.querySelectorAll('#bform [data-k]').forEach(function(i){var k=i.dataset.k;i.value=bm[k+'_'+blang]||'';bcount(k,Array.from(i.value).length,L[k])});
 $('bpal').innerHTML=bst.palettes.map(function(p){var on=(bm.palette||'technet')===p.id;return '<button type="button" data-p="'+p.id+'" aria-pressed="'+on+'"><i><b style="background:'+p.bg+'"></b><b style="background:'+p.band+'"></b><b style="background:'+p.accent+'"></b></i>'+esc(BNAME[p.id]||p.id)+'</button>'}).join('');
 $('blay').innerHTML=bst.layouts.map(function(l){var on=(bm.layout||'classic')===l,t=BLAY[l]||[l,''];return '<label><input type="radio" name="bl" value="'+l+'"'+(on?' checked':'')+'><div>'+esc(t[0])+'<span>'+esc(t[1])+'</span></div></label>'}).join('');
 $('bac').value=bm.accent||bacc();$('bacn').textContent=bm.accent?'':'Using the palette\'s own accent.';
 $('bpics').innerHTML=(bst.logo?'<img src="/brand/logo?v='+Date.now()+'" alt="Your logo"><span class="s">Logo installed</span>':'<span class="s">No logo installed yet.</span>')+(bst.picture?'<img src="/brand/picture?v='+Date.now()+'" alt="Your welcome picture"><span class="s">Welcome picture installed</span>':'');
 $('bchips').innerHTML=chip(bst.live==='branded'?'Live: your branding':'Live: the standard TechNet look',bst.live==='branded'?'ok':'')+' '+(bst.dirty?chip('Changes not published yet','gold'):'');
 $('brest').disabled=bst.live!=='branded';$('bpub').disabled=!bst.dirty}
function brand(force){if(bload||bbusy||(!force&&bm&&($('bform').contains(document.activeElement)||bsave)))return;bload=true;
 api('/api/brand').then(function(j){bload=false;if(!j||j.error)return;bst=j;bm=Object.assign({},j.draft);bshow();if(force||!$('bprev').src)bprev()}).catch(function(){bload=false})}
function bdirty(){bst.dirty=true;$('bpub').disabled=false;bmsg('');clearTimeout(bsave);bsave=setTimeout(bflush,700)}
function bflush(){clearTimeout(bsave);bsave=null;if(!bm)return Promise.resolve(false);bbusy=true;
 return api('/api/brand',bform()).then(function(j){bbusy=false;if(!j.ok){bmsg(j.why?'Not saved: '+j.why+'.':'Not saved.',true);return false}
  bmsg(j.notes&&j.notes.length?'Saved as a draft. Note: '+j.notes.join('; ')+'.':'Saved as a draft.');bprev();return true}).catch(function(){bbusy=false;return false})}
$('bn').oninput=function(){bm.name=this.value;bcount('name',Array.from(this.value).length,bst.limits.name);bdirty()};
$('bform').addEventListener('input',function(e){var k=e.target.dataset&&e.target.dataset.k;if(!k)return;bm[k+'_'+blang]=e.target.value;bcount(k,Array.from(e.target.value).length,bst.limits[k]);bdirty()});
$('bpal').addEventListener('click',function(e){var b=e.target.closest('button');if(!b)return;bm.palette=b.dataset.p;bdirty();bshow()});
$('blay').addEventListener('change',function(e){bm.layout=e.target.value;bdirty();bshow()});
$('bac').oninput=function(){bm.accent=this.value;$('bacn').textContent='';bdirty()};
$('bac0').onclick=function(){bm.accent='';bdirty();bshow()};
$('blang').addEventListener('click',function(e){var b=e.target.closest('button');if(!b)return;blang=b.dataset.l;document.querySelectorAll('#blang button').forEach(function(x){x.className=x===b?'on':''});$('bfilnote').style.display=blang==='fil'?'':'none';bshow()});
$('bpub').onclick=function(){bflush().then(function(ok){if(!ok)return;ask('Publish your branding?','Customers will see this the next time they open the page.','Publish',false,function(){api('/api/brand/publish',{}).then(function(j){if(j.ok){bmsg('Published. Customers see it the next time they open the page.');brand(true)}else bmsg(j.why?'Not published: '+j.why+'.':'Not published.',true)}).catch(function(){})})})};
$('brest').onclick=function(){ask('Go back to the standard TechNet look?','Customers see the standard page again. Your draft is kept, so you can publish it again.','Restore',true,function(){api('/api/brand/restore',{}).then(function(j){if(j.ok){bmsg('The standard page is live again. Your draft is kept.');brand(true)}else bmsg('Could not restore.',true)}).catch(function(){})})};
$('bpk').onchange=function(){var f=this.files[0],inp=this;if(!f)return;var m=$('bpkm');m.style.color='var(--mut)';
 if(f.size>300*1024){m.textContent='That file is too big to be a brand pack.';m.style.color='var(--bad)';inp.value='';return}
 var r=new FileReader();r.onload=function(){m.textContent='Checking the pack...';
  fetch('/api/brand/pack',{method:'POST',headers:{'X-Piso':'1','Content-Type':'text/plain'},body:r.result}).then(function(x){return x.json()}).then(function(j){
   if(j.ok){m.textContent='Installed. Your logo'+(j.picture_bytes?' and welcome picture are':' is')+' ready; press Publish to show '+(j.picture_bytes?'them':'it')+' to customers.';brand(true)}
   else{m.textContent='Not installed: '+(j.why||'the file was refused')+'.';m.style.color='var(--bad)'}inp.value=''}).catch(function(){m.textContent='Could not send the file.';m.style.color='var(--bad)';inp.value=''})};
 r.readAsText(f)};
function refresh(){if(tab==='brand'){brand();return}if(tab==='ph'){ph();return}var p={over:'/api/overview',cli:'/api/clients',sales:'/api/sales?days='+($('rng').value||30),vch:'/api/vouchers',set:'/api/settings'}[tab],f={over:over,cli:cli,sales:sale,vch:vch,set:sets}[tab];api(p).then(f).catch(function(){});if(tab==='set'){api('/api/pricing').then(prices).catch(function(){})}}
api('/api/overview').then(function(j){if(j.version)showApp();else showLogin()}).catch(showLogin);
})();
