import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const html=fs.readFileSync('app/src/main/assets/websdk/player.html','utf8');
const script=html.match(/<script>([\s\S]*)<\/script>/)[1];
async function env({eme=true,secure=true,probe=false,reject=false}={}) {
 const events=[],tokens=[],scripts=[],calls=[],listeners={}; let options;
 const context={SquareSdk:{event:s=>events.push(JSON.parse(s)),token:id=>tokens.push(id)},
  navigator:eme?{requestMediaKeySystemAccess:async()=>{if(reject)throw Error()}}:{},
  isSecureContext:secure,
  document:{createElement:()=>({}),head:{appendChild:s=>scripts.push(s)}},
  Spotify:{Player:function(o){options=o;return {
   addListener:(name,fn)=>listeners[name]=fn,
   connect:async()=>true,pause:async()=>calls.push('pause'),
   resume:async()=>calls.push('resume'),activateElement:async()=>calls.push('activate'),
   seek:async pos=>calls.push(['seek',pos]),disconnect:()=>calls.push('disconnect'),
   getCurrentState:async()=>null
  }} }
 };context.window=context;
 vm.runInNewContext(script.replace('__CONNECT__',String(!probe)),context);
 await new Promise(resolve=>setImmediate(resolve));
 return {context,events,tokens,scripts,calls,listeners,get options(){return options}};
}
for(const config of [{eme:false},{secure:false},{reject:true}]) {
 const e=await env(config);assert.equal(e.events[0].code,'eme_unavailable');assert.equal(e.scripts.length,0);assert.equal(e.tokens.length,0);
}
const probe=await env({probe:true});assert.equal(probe.events[0].type,'capable');assert.equal(probe.scripts.length,0);
const e=await env();assert.equal(e.scripts[0].src,'https://sdk.scdn.co/spotify-player.js');
e.context.onSpotifyWebPlaybackSDKReady();
let received=[];e.options.getOAuthToken(t=>received.push(t));e.options.getOAuthToken(t=>received.push(t));
assert.deepEqual(e.tokens,[1,2]);e.context.receiveToken(2,'fresh-two');e.context.receiveToken(1,'fresh-one');e.context.receiveToken(1,'duplicate');assert.deepEqual(received,['fresh-two','fresh-one']);
e.listeners.ready({device_id:'test-device'});assert.equal(e.events.at(-1).deviceId,'test-device');
e.listeners.player_state_changed({paused:false,position:12000,duration:180000,track_window:{current_track:{name:'Track',artists:[{name:'Artist'}]}}});assert.equal(e.events.at(-1).artist,'Artist');assert.equal(e.events.at(-1).position,12000);
await e.context.command('resume');await e.context.command('seek',15000);await e.context.command('pause');assert.deepEqual(e.calls,['activate','resume',['seek',15000],'pause']);
const endingTrack={name:'Track',uri:'spotify:track:4PTG3Z6ehGkBFwjybzWkR8',artists:[{name:'Artist'}]};
function report(paused,position){e.listeners.player_state_changed({paused,position,duration:180000,track_window:{current_track:endingTrack}});return e.events.at(-1);}
report(false,179000);assert.equal(report(true,0).ended,true);
assert.equal(report(true,0).ended,false); // Repeated polls must not advance the queue twice.
report(false,179000);report(true,179000);assert.equal(report(true,0).ended,false); // Seeking while paused is not track completion.
e.listeners.authentication_error({message:'SENSITIVE DETAILS'});assert.equal(e.events.at(-1).code,'authentication_error');assert.equal(JSON.stringify(e.events).includes('SENSITIVE'),false);
e.scripts[0].onerror();assert.equal(e.events.at(-1).code,'sdk_load_error');
console.log('PASS: DRM gates, probe without login, SDK loading, renewed token callbacks, playback events, commands and sanitized errors.');
