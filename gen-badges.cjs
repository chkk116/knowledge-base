// shields.io forTheBadge 精确复刻（badge-maker badge-renderers.js L798 + anafanafo verdana-10px 字宽表）
// 字宽为 anafanafo@2.0.0 data/verdana-10px-{normal,bold}.json 中所用字符的精确值
const fs = require('fs');
const path = require('path');

const LETTER_SPACING = 1.25, TEXT_MARGIN = 12, BADGE_HEIGHT = 28;

const NORMAL = {"0":6.36,"1":6.36,"2":6.36,"3":6.36,"4":6.36,"5":6.36,"6":6.36,"7":6.36,"8":6.36,"9":6.36,"A":6.84,"B":6.86,"C":6.98,"D":7.71,"E":6.32,"F":5.75,"G":7.75,"H":7.51,"I":4.21,"J":4.55,"K":6.93,"L":5.57,"M":8.43,"N":7.48,"O":7.87,"P":6.03,"Q":7.87,"R":6.95,"S":6.84,"T":6.16,"U":7.32,"V":6.84,"W":9.89,"X":6.85,"Y":6.15,"Z":6.85," ":3.52,".":3.64,"-":4.54,"+":8.18,"/":4.54};
const BOLD = {"0":7.11,"1":7.11,"2":7.11,"3":7.11,"4":7.11,"5":7.11,"6":7.11,"7":7.11,"8":7.11,"9":7.11,"A":7.76,"B":7.62,"C":7.24,"D":8.3,"E":6.83,"F":6.5,"G":8.11,"H":8.37,"I":5.46,"J":5.55,"K":7.71,"L":6.37,"M":9.48,"N":8.47,"O":8.5,"P":7.33,"Q":8.5,"R":7.82,"S":7.1,"T":6.82,"U":8.12,"V":7.64,"W":11.28,"X":7.64,"Y":7.37,"Z":6.92," ":3.42,".":3.61,"-":4.8,"+":8.67,"/":6.89};

// 与 badge-maker 一致：度量宽度 |0 截断，再叠加每字符 1.25 字距
const labelW = s => { let w = 0; for (const ch of s.toUpperCase()) w += NORMAL[ch] ?? 6.9; return (w | 0) + LETTER_SPACING * s.length; };
const messageW = s => { let w = 0; for (const ch of s.toUpperCase()) w += BOLD[ch] ?? 7.3; return (w | 0) + LETTER_SPACING * s.length; };

function badge(label, message, color) {
  label = label.toUpperCase();
  message = message.toUpperCase();
  const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');
  const lw = labelW(label), mw = messageW(message);
  const labelRect = 2 * TEXT_MARGIN + lw;
  const messageRect = 2 * TEXT_MARGIN + mw;
  const total = labelRect + messageRect;
  const lx = (TEXT_MARGIN + 0.5 * lw) * 10;
  const mx = (labelRect + TEXT_MARGIN + 0.5 * mw) * 10;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${total}" height="${BADGE_HEIGHT}" role="img" aria-label="${esc(label)}: ${esc(message)}"><title>${esc(label)}: ${esc(message)}</title><g shape-rendering="crispEdges"><rect width="${labelRect}" height="${BADGE_HEIGHT}" fill="#555"/><rect x="${labelRect}" width="${messageRect}" height="${BADGE_HEIGHT}" fill="#${color.toLowerCase()}"/></g><g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" text-rendering="geometricPrecision" font-size="100"><text transform="scale(.1)" x="${lx}" y="175" textLength="${lw * 10}">${esc(label)}</text><text transform="scale(.1)" x="${mx}" y="175" textLength="${mw * 10}" font-weight="bold">${esc(message)}</text></g></svg>\n`;
}

const outDir = path.join(__dirname, 'docs', 'badges');
fs.mkdirSync(outDir, { recursive: true });

const badges = [
  ['license-agpl3.svg',   'License',      'AGPL-3.0',       '3B82F6'],
  ['java.svg',            'Java',         '21',             'D97706'],
  ['spring-boot.svg',     'Spring Boot',  '4.x',            '10B981'],
  ['vue.svg',             'Vue',          '3',              '059669'],
  ['postgresql.svg',      'PostgreSQL',   '18',             '8B5CF6'],
  ['electron.svg',        'Electron',     '33',             '47848F'],
  ['inspired-khazix.svg', 'Inspired by',  'Khazix Skills',  '8B5CF6'],
];

for (const [file, label, msg, color] of badges) {
  fs.writeFileSync(path.join(outDir, file), badge(label, msg, color));
  console.log('ok', file, JSON.stringify(labelW(label)), JSON.stringify(messageW(msg)));
}
