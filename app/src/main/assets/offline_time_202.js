/* ASDS Mobile 2.0.2 – pure functions shared by offline time UI and Node regression tests. */
(function(root, factory) {
  'use strict';
  var api = factory();
  root.ASDSOfflineTimeV202 = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof window === 'undefined' ? globalThis : window, function() {
  'use strict';
  function cents(input, caption) {
    var s = String(input == null ? '' : input).trim().replace(',', '.');
    if (!/^(?:\d{1,7})(?:\.\d{1,2})?$/.test(s)) throw new Error(caption + ': bitte eine Zahl mit maximal zwei Nachkommastellen eingeben.');
    var v = Number(s);
    if (!Number.isFinite(v) || v < 0 || v > 1000000) throw new Error(caption + ': ungültiger Wert.');
    return Math.round(v * 100);
  }
  function format(c) { return (c / 100).toFixed(2); }
  function calculate(km, rate) {
    var km100 = cents(km, 'Kilometer'), rate100 = cents(rate, 'Kilometeransatz');
    // Round half up for nonnegative decimal operands. Both are exact cent integers.
    // km hundredths * CHF/km hundredths / 100 = amount in CHF cents.
    var amountCents = Math.floor((km100 * rate100 + 50) / 100);
    return { billable_km: format(km100), km: format(km100),
      km_rate: format(rate100), km_amount: format(amountCents) };
  }
  function isExtendedServer(config) {
    return !!(config && config.ok === true && config.contract_version === '1.0' &&
      config.capabilities && config.capabilities.features &&
      config.capabilities.features.billable_km_on_time === true);
  }
  function matchingAppointments(customersId, appointments) {
    var customer = String(customersId || '');
    return (Array.isArray(appointments) ? appointments : []).filter(function(item) {
      if (!item || !(Number(item.id || item.appointment_id) > 0)) return false;
      return !customer || !item.customer_id || String(item.customer_id) === customer;
    });
  }
  function buildTimePayload(original, appointments) {
    var data = Object.assign({}, original);
    var amounts = calculate(data.billable_km == null ? (data.km || '0') : data.billable_km,
                            data.km_rate);
    data.km = amounts.km;
    data.billable_km = amounts.billable_km;
    data.km_rate = amounts.km_rate;
    var appointmentId = String(data.appointment_id || '');
    if (appointmentId) {
      if (!/^\d+$/.test(appointmentId) || Number(appointmentId) < 1 ||
          !matchingAppointments(data.fk_soc, appointments).some(function(a) {
            return String(a.id || a.appointment_id) === appointmentId;
          })) throw new Error('Der Termin passt nicht zum gewählten Kunden oder ist nicht mehr offline verfügbar.');
      data.appointment_id = appointmentId;
    } else delete data.appointment_id;
    // Amount is a preview only; server and ASDS invoice calculation are authoritative.
    delete data.km_amount;
    return { data: data, preview: amounts.km_amount };
  }
  return { calculate: calculate, matchingAppointments: matchingAppointments,
    buildTimePayload: buildTimePayload, isExtendedServer: isExtendedServer };
});
