// TechNet PisoHub console: the page's behaviour. PISO_UI_VERSION rises with every change that is published (scripts/sign_console_ui.py).
window.PISO_UI_VERSION = 10;
(function(){
var $=function(i){return document.getElementById(i)},tab='over',timer=null,last={},sales=null,TABS=['over','cli','ph','sales','vch','set'];
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
function tile(k,v,s,cls){return '<div class="card"><div class="k">'+esc(k)+'</div><div class="v"'+(cls?' style="color:var(--'+cls+')"':'')+'>'+v+'</div><div class="s">'+(s||'')+'</div></div>'}
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
function over(j){last.over=j;money(j.money);var m=j.week||[],wk=m.reduce(function(a,b){return a+b},0);
 $('hm').textContent=peso(j.today);$('hs').textContent='This week '+peso(wk)+' · all time '+peso(j.total);
 $('tiles').innerHTML=tile('Customers online',j.online,j.waiting+' waiting to pay')+tile('Internet',sp(j.rx_kbps),'down; up '+sp(j.tx_kbps))+
  tile('Coin box',j.box_ok?'OK':'Offline',j.box_ok?(j.box_signed?'answering, answers signed':'answering. Update its firmware: answers are not signed yet'):'check power and Wi-Fi',j.box_ok?'ok':'bad')+tile('Records',j.ledger_ok?'OK':'Check',j.ledger_lines+' lines'+(j.ledger_ok?'':' (line '+j.ledger_lines+' is wrong)'),j.ledger_ok?'ok':'bad')+
  (j.ledger_kept!==undefined?tile('Sales detail',j.ledger_kept+(j.ledger_kept===1?' month':' months'),j.ledger_pruned?(j.ledger_pruned+(j.ledger_pruned===1?' older month was':' older months were')+' deleted to save space. Your total ('+peso(j.total)+') and each deleted month\'s total are kept.'+((j.ledger_kept-1<j.ledger_wanted)?' The space allows fewer than the '+j.ledger_wanted+' closed months you asked for (Settings).':'')):'nothing deleted'):'')+
  tile('Router',Math.floor(j.uptime/3600)+' h','up; '+Math.round(j.mem_kb/1024)+' MB memory free')+
  ((j.alerts&&j.alerts.length)?tile('Alerts',j.alerts.length,j.alerts.map(esc).join('<br>'),'gold'):'');draw(j.history)}
function cli(j){var a=j.clients||[];$('cl').innerHTML=a.map(function(c){var on=c.state==='Authenticated',nm=c.name||c.mac;
 return '<tr><td><b>'+esc(nm)+'</b><div class="s">'+esc(c.mac)+'</div></td><td class="hm">'+esc(c.ip)+'</td><td>'+(on?(c.paused?chip('paused','gold'):chip(c.plan||'online',c.plan==='endurance'?'gold':'ok')):chip('waiting'))+'</td><td class="n">'+tm(c.left)+'</td><td class="n hm">'+mb(c.dl_kb+c.ul_kb)+'</td><td class="n hm">'+(on?'&darr; '+sp(c.dl_kbps)+' &uarr; '+sp(c.ul_kbps):'-')+'</td><td>'+(on?'<button class="b" data-mac="'+esc(c.mac)+'" data-nm="'+esc(nm)+'">Disconnect</button>':'')+'</td></tr>'}).join('')||'<tr><td colspan="7" class="s">Nobody is connected right now.</td></tr>';
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
 $('sd').innerHTML=d.map(function(x,i){return '<div>'+(d.length<=10||i%Math.ceil(d.length/10)===0?x.d.slice(5).replace('-','/'):'')+'</div>'}).join('');
 $('ssum').textContent=best&&best.t?'Best day: '+best.d+', '+peso(best.t)+'.':'No sales in this period.';
 $('shd').innerHTML='<tr><th>Day</th><th class="n">Use now</th><th class="n">Voucher codes</th>'+(vo?'<th class="n">Old slips</th>':'')+'<th class="n">Total</th><th class="n hm">Payments</th></tr>';
 $('sl').innerHTML=d.slice().reverse().map(function(x){return '<tr><td>'+esc(x.d)+'</td><td class="n">'+peso(x.coin)+'</td><td class="n">'+peso(x.vcoin||0)+(x.vi?' <span class="s">('+x.vi+')</span>':'')+'</td>'+(vo?'<td class="n">'+peso(x.voucher)+'</td>':'')+'<td class="n"><b>'+peso(tot(x))+'</b></td><td class="n hm">'+x.n+'</td></tr>'}).join('')}
$('rng').onchange=refresh;
$('csv').onclick=function(){if(!sales)return;var t='day,use_now,voucher_codes,vouchers_issued,old_slips,total,payments\n'+sales.days.map(function(x){return [x.d,x.coin,x.vcoin||0,x.vi||0,x.voucher,tot(x),x.n].join(',')}).join('\n')+'\n',a=document.createElement('a');a.href=URL.createObjectURL(new Blob([t],{type:'text/csv'}));a.download='sales-'+$('rng').value+'-days.csv';a.click()};
/* vouchers: the list of what was issued (no codes), and cancelling an unused one */
function vch(j){if(soldOut(j))return;var a=j.batches||[];$('vl2').innerHTML=a.slice().reverse().map(function(b){var left=b.count-b.used,gone=b.expires&&b.expires*1e3<Date.now();
 return '<tr><td><b>'+(b.bought===false?esc(b.label||'Printed slips'):'Bought with coins')+'</b><div class="s">'+dt(b.created)+(b.count>1?' · '+b.count+' codes':'')+' · '+(b.plan==='endurance'?'Endurance':'HyperSpeed')+'</div></td><td>'+mins(b.minutes)+' · '+peso(b.pesos)+'</td><td>'+(b.void?chip('Cancelled','bad'):!left?chip('Used','ok'):b.used?chip(b.used+' of '+b.count+' used'):chip('Not used','gold'))+'</td><td>'+(gone?chip('Expired','bad'):b.expires?dt(b.expires):'No expiry')+'</td><td>'+(b.void||!left||gone?'':'<button class="b dg" data-v="'+b.id+'" data-n="'+esc(b.bought===false?(b.label||b.id):dt(b.created))+'" data-l="'+left+'">Cancel</button>')+'</td></tr>'}).join('')||'<tr><td colspan="5" class="s">No vouchers yet. Customers get one when they choose "Get a voucher code" at payment.</td></tr>';
 document.querySelectorAll('#vl2 button[data-v]').forEach(function(b){b.onclick=function(){ask('Cancel this voucher ('+b.dataset.n+')?',b.dataset.l+' unused code'+(b.dataset.l==='1'?'':'s')+' will stop working. The customer already paid; the money stays in the sales.','Cancel the voucher',true,function(){api('/api/vouchers/void',{batch:b.dataset.v}).then(refresh)})}})}
/* settings */
function sets(j){if(soldOut(j))return;if(document.activeElement!==$('vcs'))$('vcs').value=j.voucher_choice?'on':'off';if(document.activeElement!==$('fss')&&j.file_send!==undefined)$('fss').value=j.file_send?'on':'off';if(document.activeElement!==$('lkb')&&j.ledger_max_kb!==undefined)$('lkb').value=j.ledger_max_kb;if(document.activeElement!==$('lkm')&&j.ledger_keep_months!==undefined)$('lkm').value=j.ledger_keep_months;if(j.file_send_key!==undefined)$('fsk').textContent=j.file_send_key?(j.file_send?'Customers can send files now.':''):'The print server key is not installed on this router, so the button stays hidden even when this is On (see the manual: Sending a file to the owner).'}
$('fss').onchange=function(){var s=$('fss');$('setm').textContent='';api('/api/settings',{file_send:s.value}).then(function(j){$('setm').textContent=j.ok?'Saved. Open customer pages follow at once.':'Could not save.';if(j.ok){s.value=j.file_send?'on':'off';sets(j)}}).catch(function(){})};
$('lkb').onchange=function(){var m=Math.round(+$('lkb').value);if(!(m===0||(m>=256&&m<=16384))){$('setm').textContent='Use 0, or a number from 256 to 16384.';return}$('setm').textContent='';api('/api/settings',{ledger_max_kb:String(m)}).then(function(j){$('setm').textContent=j.ok?'Saved. Old months are deleted when the next month starts.':(j.why||'Could not save.');if(j.ok)sets(j)}).catch(function(){})};
$('lkm').onchange=function(){var m=Math.round(+$('lkm').value);if(!(m>=1&&m<=60)){$('setm').textContent='Use a number from 1 to 60.';return}$('setm').textContent='';api('/api/settings',{ledger_keep_months:String(m)}).then(function(j){$('setm').textContent=j.ok?'Saved.':(j.why||'Could not save.');if(j.ok)sets(j)}).catch(function(){})};
$('vcs').onchange=function(){var s=$('vcs');$('setm').textContent='';api('/api/settings',{voucher_choice:s.value}).then(function(j){$('setm').textContent=j.ok?'Saved. Open customer pages follow at once.':'Could not save.';if(j.ok)s.value=j.voucher_choice?'on':'off'}).catch(function(){})};
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
function refresh(){if(tab==='ph'){ph();return}var p={over:'/api/overview',cli:'/api/clients',sales:'/api/sales?days='+($('rng').value||30),vch:'/api/vouchers',set:'/api/settings'}[tab],f={over:over,cli:cli,sales:sale,vch:vch,set:sets}[tab];api(p).then(f).catch(function(){});if(tab==='set'){api('/api/pricing').then(prices).catch(function(){})}}
api('/api/overview').then(function(j){if(j.version)showApp();else showLogin()}).catch(showLogin);
})();
