// TechNet PisoHub console: the page's behaviour. PISO_UI_VERSION rises with every change that is published (scripts/sign_console_ui.py).
window.PISO_UI_VERSION = 1;
(function(){
var $=function(i){return document.getElementById(i)},tab='over',timer=null,last={};
function esc(t){return String(t).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]})}
function api(path,body){var o=body===undefined?{}:{method:'POST',headers:{'X-Piso':'1','Content-Type':'application/json'},body:JSON.stringify(body)};
 return fetch(path,o).then(function(r){if(r.status===401&&path!=='/api/login'){showLogin();throw new Error('login')}return r.json().then(function(j){j._code=r.status;return j})})}
function mb(kb){kb=+kb||0;return kb>=1048576?(kb/1048576).toFixed(1)+' GB':kb>=1024?(kb/1024).toFixed(1)+' MB':kb+' kB'}
function sp(k){k=+k||0;return k>=1000?(k/1000).toFixed(1)+' Mbps':k+' kbps'}
function tm(s){s=+s||0;if(s<=0)return '-';var h=Math.floor(s/3600),m=Math.floor(s%3600/60);return h?h+'h '+m+'m':m+' min'}
function peso(n){return '₱'+(+n||0).toLocaleString()}
function showLogin(){clearInterval(timer);$('app').className='hide';$('login').className='';$('pw').focus()}
function showApp(){$('login').className='hide';$('app').className='';$('adv').href='http://'+location.hostname+(location.port==='8081'?'/':':8080/');refresh();clearInterval(timer);timer=setInterval(refresh,4000)}
$('gobtn').onclick=function(){api('/api/login',{password:$('pw').value}).then(function(j){if(j.ok){$('pw').value='';$('err').textContent='';showApp()}else $('err').textContent=j.error==='LOCKED'?'Too many tries. Wait '+j.retry+' s.':'Wrong password.'}).catch(function(){$('err').textContent='The router did not answer.'})};
$('pw').onkeydown=function(e){if(e.key==='Enter')$('gobtn').onclick()};
$('out').onclick=function(){api('/api/logout',{}).then(showLogin)};
document.querySelectorAll('#tabs button').forEach(function(b){b.onclick=function(){tab=b.dataset.t;document.querySelectorAll('#tabs button').forEach(function(x){x.className=x===b?'on':''});['over','cli','ph','money'].forEach(function(t){$('t-'+t).className=t===tab?'':'hide'});refresh()}});
function tile(k,v,s,cls){return '<div class="card tile"><div class="k">'+esc(k)+'</div><div class="v"'+(cls?' style="color:var(--'+cls+')"':'')+'>'+v+'</div><div class="s">'+(s||'')+'</div></div>'}
function draw(h){var c=$('cv'),g=c.getContext('2d'),w=c.width,ht=c.height;g.clearRect(0,0,w,ht);if(!h||h.length<2){return}
 var mx=1;h.forEach(function(p){mx=Math.max(mx,p[1],p[2])});
 [[1,'#2f7bff'],[2,'#16c9a4']].forEach(function(l){g.beginPath();h.forEach(function(p,i){var x=i*(w/(h.length-1)),y=ht-4-(ht-12)*p[l[0]]/mx;i?g.lineTo(x,y):g.moveTo(x,y)});g.strokeStyle=l[1];g.lineWidth=3;g.stroke()});
 $('cvl').innerHTML='<span style="color:#2f7bff">&#9632;</span> download &nbsp; <span style="color:#16c9a4">&#9632;</span> upload &nbsp; (last ~'+Math.round((h[h.length-1][0]-h[0][0])/60)+' min, peak '+sp(mx)+')'}
function over(j){last.over=j;
 $('tiles').innerHTML=tile('Customers online',j.online,j.waiting+' waiting to pay')+tile('Money today',peso(j.today),'Total '+peso(j.total))+tile('Internet down',sp(j.rx_kbps),'up '+sp(j.tx_kbps))+
  tile('Coin box',j.box_ok?'OK':'Offline',j.box_ok?'answering':'check power and Wi-Fi',j.box_ok?'ok':'bad')+tile('Records',j.ledger_ok?'OK':'Check',j.ledger_lines+' lines'+(j.ledger_ok?'':' (line '+j.ledger_lines+' is wrong)'),j.ledger_ok?'ok':'bad')+
  tile('Router',Math.floor(j.uptime/3600)+' h','up; '+Math.round(j.mem_kb/1024)+' MB memory free');draw(j.history);
 var m=j.week||[],mx=Math.max.apply(null,m.concat([1])),n=new Date();$('mt').innerHTML=tile('Today',peso(j.today))+tile('This week',peso(m.reduce(function(a,b){return a+b},0)))+tile('All time',peso(j.total));
 $('bars').innerHTML=m.map(function(v){return '<div style="height:'+Math.max(2,Math.round(v/mx*86))+'px"><span>'+(v?peso(v):'')+'</span></div>'}).join('');
 var d=['Sun','Mon','Tue','Wed','Thu','Fri','Sat'];$('days').innerHTML=m.map(function(_,i){return '<div>'+d[(new Date(Date.now()-(6-i)*864e5)).getDay()]+'</div>'}).join('')}
function cli(j){var a=j.clients||[];$('cl').innerHTML=a.map(function(c){var on=c.state==='Authenticated',nm=c.name||c.mac;
 return '<tr><td><b>'+esc(nm)+'</b><div class="s">'+esc(c.mac)+'</div></td><td class="hm">'+esc(c.ip)+'</td><td>'+(on?'<span class="pill '+(c.plan==='endurance'?'gold':'ok')+'">'+esc(c.plan||'online')+'</span>':'<span class="pill">waiting</span>')+'</td><td class="n">'+tm(c.left)+'</td><td class="n">'+mb(c.dl_kb+c.ul_kb)+'</td><td class="n">'+(on?'&darr; '+sp(c.dl_kbps)+'<br>&uarr; '+sp(c.ul_kbps):'-')+'</td><td>'+(on?'<button class="b" data-mac="'+esc(c.mac)+'">Disconnect</button>':'')+'</td></tr>'}).join('')||'<tr><td colspan="7" class="s">Nobody is connected right now.</td></tr>';
 $('clnote').textContent='Updates every few seconds. Speeds are what each device uses right now.';
 document.querySelectorAll('#cl button.b').forEach(function(b){b.onclick=function(){if(confirm('Disconnect this device? Its paid time stays; it can reconnect.'))api('/api/kick',{mac:b.dataset.mac}).then(refresh)}})}
function ph(j){var n=$('phnote');if(j.error){$('phl').innerHTML='';n.textContent=j.error==='PASSWORD'?'The router does not know the coin box password any more: run piso-setup kiosk-wifi on the router.':j.error==='NO_PASSWORD'?'The coin box password is not stored on this router.':'The coin box does not answer.';return}
 n.textContent='';var a=Array.isArray(j)?j:(j.devices||[]);$('phl').innerHTML=a.map(function(d){if(!d.isBound)return '<tr><td>'+d.slotNum+'</td><td class="s">Empty slot</td><td></td><td></td><td></td></tr>';
 var nm=(d.name&&d.name.indexOf('Terminal')!==0)?d.name:'PisoPhone '+d.slotNum;return '<tr><td>'+d.slotNum+'</td><td><b>'+esc(nm)+'</b><div class="s">'+esc(d.ip||'')+'</div></td><td>'+(!d.online?'<span class="pill bad">Offline</span>':d.time>0?'<span class="pill ok">Renting</span>':'<span class="pill">Locked</span>')+'</td><td class="n">'+(d.online?tm(d.time):'-')+'</td><td class="n">'+(typeof d.battery==='number'&&d.battery>=0?d.battery+'%'+(d.charging?' &#9889;':''):'-')+'</td></tr>'}).join('')||'<tr><td colspan="5" class="s">No phones yet.</td></tr>'}
function refresh(){var p={over:'/api/overview',cli:'/api/clients',ph:'/api/phones',money:'/api/overview'}[tab],f={over:over,cli:cli,ph:ph,money:over}[tab];api(p).then(f).catch(function(){})}
api('/api/overview').then(function(j){if(j.version)showApp();else showLogin()}).catch(showLogin);
})();
