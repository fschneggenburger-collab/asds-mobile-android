/* Regression tests for the actual asset packaged in the Android APK. */
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const api = require('../app/src/main/assets/offline_time_202.js');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/offline_capture.html'), 'utf8');
const kotlin = fs.readFileSync(path.join(__dirname, '../app/src/main/java/ch/asds/mobile/MainActivity.kt'), 'utf8');
function eq(km, rate, amount) {
  assert.equal(api.calculate(km, rate).km_amount, amount, km + ' * ' + rate);
}
eq('3.35', '0.70', '2.35');
eq('12.50', '0.80', '10.00');
eq('1,25', '0,85', '1.06');
eq('0', '0.70', '0.00');
eq('2.00', '0.00', '0.00');
for (const bad of ['-1', '1.005', 'abc', '1e4', 'Infinity', '']) {
  assert.throws(() => api.calculate(bad, '0.70'));
}
const appointments = [
  { id: 12, label: 'Kunde A', customer_id: 1 },
  { id: 13, label: 'Kunde B', customer_id: 2 },
  { id: 14, label: 'ohne Kunde', customer_id: null }
];
assert.deepEqual(api.matchingAppointments('1', appointments).map(a => a.id), [12,14]);
assert.equal(api.isExtendedServer({ok:true,contract_version:'1.0',
  capabilities:{features:{billable_km_on_time:true}}}),true);
assert.equal(api.isExtendedServer({ok:true,server_version:'2.0.0'}),false);
const input = {fk_soc:'1',work_date:'2026-11-01',start_time:'09:00',end_time:'10:00',
  description:'Begleitung',billable:1,billable_km:'3,35',km_rate:'0,70',
  appointment_id:'12'};
const original = JSON.stringify(input);
const out = api.buildTimePayload(input, appointments);
assert.equal(out.data.km, '3.35');
assert.equal(out.data.billable_km,'3.35');
assert.equal(out.data.km_rate,'0.70');
assert.equal(out.data.appointment_id,'12');
assert.equal(out.preview,'2.35');
assert.equal(JSON.stringify(input),original, 'must not mutate original draft');
assert.equal(Object.hasOwn(out.data,'km_amount'),false, 'server recalculates amount');
assert.throws(()=>api.buildTimePayload({...input,appointment_id:'13'},appointments));
const noAppointment = api.buildTimePayload({...input,appointment_id:''},appointments);
assert.equal(Object.hasOwn(noAppointment.data,'appointment_id'),false);
for (const field of ["['billable_km'","['km_rate'","['appointment_id'"]) {
  assert.ok(html.includes(field), 'offline time field missing '+field);
}
assert.ok(html.includes('ASDSOfflineTimeV202.buildTimePayload(data,C.appointments)'));
assert.ok(html.includes('ASDSOfflineTimeV202.matchingAppointments('));
assert.ok(html.includes('id="km-preview"'));
assert.ok(html.includes('enqueueForm'), 'existing queue must stay in place');
assert.ok(kotlin.includes('assets.open("offline_time_202.js")'), 'file must be read from assets');
assert.ok(kotlin.includes('baseHtml.replace(scriptTag'), 'JS must be injected locally');
assert.ok(!html.includes('https://portal.ihre-wegbegleiterin.ch/mobile/offline_time_202.js'));
console.log('Offline 2.0.2 regression tests passed: amounts, customers, payload, asset injection and queue.');
