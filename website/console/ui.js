// TechNet PisoHub console: the page's behaviour. PISO_UI_VERSION rises with every change that is published (scripts/sign_console_ui.py).
window.PISO_UI_VERSION = 2;
(function(){
var $=function(i){return document.getElementById(i)},tab='over',timer=null,last={},sales=null,TABS=['over','cli','ph','sales','vch'];
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
$('th').onclick=function(){var r=document.documentElement,d=(r.dataset.theme||(matchMedia('(prefers-color-scheme:dark)').matches?'dark':'light'))==='dark';r.dataset.theme=d?'light':'dark';try{localStorage.setItem('piso_theme',r.dataset.theme)}catch(e){}if(last.over)draw(last.over.history)};
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
function over(j){last.over=j;var m=j.week||[],wk=m.reduce(function(a,b){return a+b},0);
 $('hm').textContent=peso(j.today);$('hs').textContent='This week '+peso(wk)+' · all time '+peso(j.total);
 $('tiles').innerHTML=tile('Customers online',j.online,j.waiting+' waiting to pay')+tile('Internet',sp(j.rx_kbps),'down; up '+sp(j.tx_kbps))+
  tile('Coin box',j.box_ok?'OK':'Offline',j.box_ok?(j.box_signed?'answering, answers signed':'answering. Update its firmware: answers are not signed yet'):'check power and Wi-Fi',j.box_ok?'ok':'bad')+tile('Records',j.ledger_ok?'OK':'Check',j.ledger_lines+' lines'+(j.ledger_ok?'':' (line '+j.ledger_lines+' is wrong)'),j.ledger_ok?'ok':'bad')+
  tile('Router',Math.floor(j.uptime/3600)+' h','up; '+Math.round(j.mem_kb/1024)+' MB memory free');draw(j.history)}
function cli(j){var a=j.clients||[];$('cl').innerHTML=a.map(function(c){var on=c.state==='Authenticated',nm=c.name||c.mac;
 return '<tr><td><b>'+esc(nm)+'</b><div class="s">'+esc(c.mac)+'</div></td><td class="hm">'+esc(c.ip)+'</td><td>'+(on?chip(c.plan||'online',c.plan==='endurance'?'gold':'ok'):chip('waiting'))+'</td><td class="n">'+tm(c.left)+'</td><td class="n hm">'+mb(c.dl_kb+c.ul_kb)+'</td><td class="n hm">'+(on?'&darr; '+sp(c.dl_kbps)+' &uarr; '+sp(c.ul_kbps):'-')+'</td><td>'+(on?'<button class="b" data-mac="'+esc(c.mac)+'" data-nm="'+esc(nm)+'">Disconnect</button>':'')+'</td></tr>'}).join('')||'<tr><td colspan="7" class="s">Nobody is connected right now.</td></tr>';
 $('clnote').textContent='Updates every few seconds. A disconnected customer keeps the paid time and can connect again.';
 document.querySelectorAll('#cl button.b').forEach(function(b){b.onclick=function(){ask('Disconnect '+b.dataset.nm+'?','Their paid time stays; they can reconnect.','Disconnect '+b.dataset.nm,true,function(){api('/api/kick',{mac:b.dataset.mac}).then(refresh)})}})}
function ph(j){var n=$('phnote');if(j.error){$('phl').innerHTML='';n.textContent=j.error==='PASSWORD'?'The router does not know the coin box password any more: run piso-setup kiosk-wifi on the router.':j.error==='NO_PASSWORD'?'The coin box password is not stored on this router.':'The coin box does not answer.';return}
 n.textContent='';var a=Array.isArray(j)?j:(j.devices||[]);$('phl').innerHTML=a.map(function(d){if(!d.isBound)return '<tr><td>'+d.slotNum+'</td><td class="s">Empty slot</td><td></td><td></td><td></td></tr>';
 var nm=(d.name&&d.name.indexOf('Terminal')!==0)?d.name:'PisoPhone '+d.slotNum;return '<tr><td>'+d.slotNum+'</td><td><b>'+esc(nm)+'</b><div class="s">'+esc(d.ip||'')+'</div></td><td>'+(!d.online?chip('Offline','bad'):d.time>0?chip('Renting','ok'):chip('Locked'))+'</td><td class="n">'+(d.online?tm(d.time):'-')+'</td><td class="n">'+(typeof d.battery==='number'&&d.battery>=0?d.battery+'%'+(d.charging?' &#9889;':''):'-')+'</td></tr>'}).join('')||'<tr><td colspan="5" class="s">No phones yet.</td></tr>'}
/* sales */
function soldOut(j){if(j&&j.error==='NOT_FOUND'){$('t-'+tab).insertAdjacentHTML('afterbegin','<p class="msg">This router is on an older version. Run <b>piso-setup update</b> to use this page.</p>');return true}return false}
function sale(j){if(soldOut(j))return;sales=j;var d=j.days||[],mx=1,co=j.kinds.coin,vo=j.kinds.voucher,n=d.reduce(function(a,x){return a+x.n},0),best=null;
 d.forEach(function(x){var t=x.coin+x.voucher;if(t>mx)mx=t;if(!best||t>best.t)best={d:x.d,t:t}});
 $('st').innerHTML=tile('Total',peso(co+vo),n+' payments','gold')+tile('Coins',peso(co),'HyperSpeed '+peso(j.plans.hyper)+' · Endurance '+peso(j.plans.endurance))+tile('Vouchers',peso(vo),'sold as printed codes');
 $('sb').innerHTML=d.map(function(x){var t=x.coin+x.voucher;return '<div title="'+esc(x.d)+': '+peso(t)+'" style="height:'+Math.max(2,Math.round(t/mx*104))+'px">'+(x===d[d.length-1]&&t?'<span>'+peso(t)+'</span>':'')+'</div>'}).join('');
 $('sd').innerHTML=d.map(function(x,i){return '<div>'+(d.length<=10||i%Math.ceil(d.length/10)===0?x.d.slice(5).replace('-','/'):'')+'</div>'}).join('');
 $('ssum').textContent=best&&best.t?'Best day: '+best.d+', '+peso(best.t)+'.':'No sales in this period.';
 $('sl').innerHTML=d.slice().reverse().map(function(x){return '<tr><td>'+esc(x.d)+'</td><td class="n">'+peso(x.coin)+'</td><td class="n">'+peso(x.voucher)+'</td><td class="n"><b>'+peso(x.coin+x.voucher)+'</b></td><td class="n hm">'+x.n+'</td></tr>'}).join('')}
$('rng').onchange=refresh;
$('csv').onclick=function(){if(!sales)return;var t='day,coins,vouchers,total,payments\n'+sales.days.map(function(x){return [x.d,x.coin,x.voucher,x.coin+x.voucher,x.n].join(',')}).join('\n')+'\n',a=document.createElement('a');a.href=URL.createObjectURL(new Blob([t],{type:'text/csv'}));a.download='sales-'+$('rng').value+'-days.csv';a.click()};
/* vouchers */
function vch(j){if(soldOut(j))return;var a=j.batches||[];$('vl2').innerHTML=a.slice().reverse().map(function(b){var left=b.count-b.used;
 return '<tr><td><b>'+esc(b.label||'Batch '+b.id)+'</b><div class="s">'+dt(b.created)+' · '+b.count+' codes · '+(b.plan==='endurance'?'Endurance':'HyperSpeed')+'</div></td><td>'+mins(b.minutes)+' · '+peso(b.pesos)+'</td><td class="n">'+b.used+' of '+b.count+'</td><td>'+(b.void?chip('Cancelled','bad'):b.expires&&b.expires*1e3<Date.now()?chip('Expired','bad'):b.expires?dt(b.expires):'No expiry')+'</td><td style="white-space:nowrap"><button class="b" data-p="a4" data-b="'+b.id+'">Print A4</button> <button class="b" data-p="58" data-b="'+b.id+'">58 mm</button>'+(b.void||!left?'':' <button class="b dg" data-v="'+b.id+'" data-n="'+esc(b.label||b.id)+'" data-l="'+left+'">Cancel</button>')+'</td></tr>'}).join('')||'<tr><td colspan="5" class="s">No vouchers yet. Make a batch above.</td></tr>';
 document.querySelectorAll('#vl2 button[data-p]').forEach(function(b){b.onclick=function(){printBatch(b.dataset.b,b.dataset.p)}});
 document.querySelectorAll('#vl2 button[data-v]').forEach(function(b){b.onclick=function(){ask('Cancel '+b.dataset.n+'?',b.dataset.l+' unused codes stop working. Codes already used keep their time.','Cancel the unused codes',true,function(){api('/api/vouchers/void',{batch:b.dataset.v}).then(refresh)})}})}
$('vmk').onclick=function(){var b=$('vmk');b.disabled=true;$('verr').textContent='';$('verr').className='err';
 api('/api/vouchers/new',{minutes:$('vm').value,pesos:$('vp').value,plan:$('vl').value,count:$('vn').value,days:$('vd').value,label:$('vb').value}).then(function(j){b.disabled=false;
  if(j.ok){$('vb').value='';$('verr').className='s';$('verr').textContent='Made. Print the slips from the list below.';refresh()}else $('verr').textContent={COUNT:'How many: 1 to 500.',MINUTES:'Pick a time.',PRICE:'That price is too high.',PLAN:'Pick a plan.',FULL:'The router holds at most 5000 codes: cancel old batches first.'}[j.error]||'Could not make the codes.'}).catch(function(){b.disabled=false})};
/* the slips: a page of A4 slips (3 across) or one narrow slip per receipt (58 mm), printed from a new window */
function printBatch(id,kind){var w=window.open('','_blank');if(!w)return;api('/api/vouchers/codes?batch='+encodeURIComponent(id)).then(function(j){if(!j.codes){w.close();return}var narrow=kind==='58',host=location.hostname;
 var s=j.codes.filter(function(c){return !c.used}).map(function(c){return '<div class="s"><div class="h"><b>TechNet</b> PisoWifi</div><div class="c">'+esc(c.c)+'</div><div class="p"><b>'+peso(j.pesos)+'</b> · '+esc(mins(j.minutes))+' · '+(j.plan==='endurance'?'Endurance':'HyperSpeed')+'</div><div class="n">'+(j.expires?'Use before '+esc(dt(j.expires)):'No expiry')+'<br>Join the Wi-Fi, open any page, tap <b>Have a voucher?</b> and type the code.</div></div>'}).join('');
 w.document.write('<!doctype html><title>'+esc(j.label||'Vouchers')+'</title><style>body{margin:0;font:12px/16px system-ui,sans-serif;color:#000}@page{margin:'+(narrow?'0':'10mm')+';size:'+(narrow?'58mm auto':'A4')+'}.g{display:grid;grid-template-columns:'+(narrow?'1fr':'repeat(3,1fr)')+';gap:0}.s{border:1px dashed #888;padding:'+(narrow?'3mm':'4mm')+';height:'+(narrow?'auto':'33mm')+';break-inside:avoid;overflow:hidden}.h{background:#0a1b45;color:#fff;padding:2px 6px;margin:-4mm -4mm 6px;font-size:12px}.c{font:700 17px/22px ui-monospace,Menlo,Consolas,monospace;letter-spacing:.06em;margin:4px 0}.p{font-size:12px}.n{font-size:9px;line-height:11px;color:#333;margin-top:3px}'+(narrow?'.h{margin:-3mm -3mm 6px}':'')+'</style><div class="g">'+s+'</div>');w.document.close();w.focus();setTimeout(function(){w.print()},300)})}
function refresh(){var p={over:'/api/overview',cli:'/api/clients',ph:'/api/phones',sales:'/api/sales?days='+($('rng').value||30),vch:'/api/vouchers'}[tab],f={over:over,cli:cli,ph:ph,sales:sale,vch:vch}[tab];api(p).then(f).catch(function(){})}
api('/api/overview').then(function(j){if(j.version)showApp();else showLogin()}).catch(showLogin);
})();
