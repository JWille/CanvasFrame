// PC checks for the ported libraries: node webos/server/test/lib-test.js
'use strict';
const assert = require('assert');
const ics = require('../lib/ics'), auth = require('../lib/auth'), qr = require('../lib/qr');
// RFC 6238 SHA-1 vector: secret "12345678901234567890", T = 59 s -> 94287082 (8 digits) -> 287082 (6)
assert.strictEqual(auth.totp(Buffer.from('12345678901234567890'), Math.floor(59 / 30)), 287082);
assert.strictEqual(auth.totp(Buffer.from('12345678901234567890'), Math.floor(1111111109 / 30)), 81804);   // 07081804
assert.strictEqual(auth.base32Decode(auth.base32(Buffer.from('hello world!'))).toString(), 'hello world!');
// ICS: weekly in New York across the March DST change keeps 9:00 local; EXDATE skips one; all-day event
const src = `BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:a\r\nSUMMARY:Standup\r\nDTSTART;TZID=America/New_York:20260302T090000\r\nDTEND;TZID=America/New_York:20260302T093000\r\nRRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=4\r\nEXDATE;TZID=America/New_York:20260316T090000\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:b\r\nSUMMARY:Holiday\r\nDTSTART;VALUE=DATE:20260320\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:c\r\nSUMMARY:Monthly\r\nDTSTART:20260131T150000Z\r\nRRULE:FREQ=MONTHLY;COUNT=3\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:d\r\nSUMMARY:Second Tue\r\nDTSTART;TZID=Eastern Standard Time:20260210T180000\r\nRRULE:FREQ=MONTHLY;BYDAY=2TU;UNTIL=20260415T000000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n`;
const evs = ics.parse(src, 0, Date.UTC(2026, 0, 1), Date.UTC(2026, 6, 1));
const ny = t => new Date(t).toLocaleString('en-US', { timeZone: 'America/New_York', weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
const by = n => evs.filter(e => e.title === n);
console.log('Standup:', by('Standup').map(e => ny(e.start)).join(' | '));
assert.deepStrictEqual(by('Standup').map(e => ny(e.start)), ['Mon, Mar 2, 9:00 AM', 'Mon, Mar 9, 9:00 AM', 'Mon, Mar 23, 9:00 AM']);
assert.strictEqual(by('Standup')[0].end - by('Standup')[0].start, 30 * 60000);
assert.ok(by('Holiday')[0].allDay && by('Holiday')[0].end - by('Holiday')[0].start === 86400000);
console.log('Monthly (31st):', by('Monthly').map(e => new Date(e.start).toISOString()).join(' | '));
assert.strictEqual(by('Monthly').length, 3);   // Jan 31, Mar 31, May 31: months without a 31st are skipped and not counted
console.log('2nd Tue:', by('Second Tue').map(e => ny(e.start)).join(' | '));
assert.deepStrictEqual(by('Second Tue').map(e => ny(e.start)), ['Tue, Feb 10, 6:00 PM', 'Tue, Mar 10, 6:00 PM', 'Tue, Apr 14, 6:00 PM']);
// QR: write a PNG-free matrix dump for the decoder
require('fs').writeFileSync(process.argv[2] || 'qr.json', JSON.stringify(qr.matrix('otpauth://totp/CanvasFrame:Living%20room?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=CanvasFrame&algorithm=SHA1&digits=6&period=30')));
console.log('all lib tests passed');
