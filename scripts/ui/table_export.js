/* Small OOXML writer: strings only, UTF-8 ZIP entries, no formulas or external references. */
(function (root) {
  'use strict';
  var encoder = new TextEncoder();
  function checked(rows) {
    var cells = 0, characters = 0;
    if (rows.length > 1048576) { throw new Error('exportLimit'); }
    rows.forEach(function (row) {
      if (row.length > 16384) { throw new Error('exportLimit'); }
      cells += row.length;
      row.forEach(function (value) { characters += String(value == null ? '' : value).length; });
    });
    if (cells > 250000 || characters > 16000000) { throw new Error('exportLimit'); }
    return rows.map(function (row) { return row.map(function (value) { return String(value == null ? '' : value); }); });
  }
  function csv(rows) {
    return '\ufeff' + checked(rows).map(function (row) {
      return row.map(function (value) {
        // Excel interprets CSV fields even when quoted; keep formula-like input as text.
        if (/^[\s\u0000-\u0020]*[=+\-@]/.test(value)) { value = "'" + value; }
        return '"' + value.replace(/"/g, '""') + '"';
      }).join(',');
    }).join('\r\n') + '\r\n';
  }
  function xml(value) {
    return String(value).replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\ufffe\uffff]/g, '')
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function column(index) {
    var value = '';
    do { value = String.fromCharCode(65 + index % 26) + value; index = Math.floor(index / 26) - 1; } while (index >= 0);
    return value;
  }
  var crcTable = Array.from({ length: 256 }, function (_, n) {
    for (var bit = 0; bit < 8; bit++) { n = n & 1 ? 0xedb88320 ^ (n >>> 1) : n >>> 1; }
    return n >>> 0;
  });
  function crc(data) {
    var value = 0xffffffff;
    data.forEach(function (byte) { value = crcTable[(value ^ byte) & 255] ^ (value >>> 8); });
    return (value ^ 0xffffffff) >>> 0;
  }
  function header(size, fields) {
    var bytes = new Uint8Array(size), view = new DataView(bytes.buffer);
    fields.forEach(function (entry) { view[entry[2] === 2 ? 'setUint16' : 'setUint32'](entry[0], entry[1], true); });
    return bytes;
  }
  function concat(parts) {
    var result = new Uint8Array(parts.reduce(function (n, part) { return n + part.length; }, 0)), offset = 0;
    parts.forEach(function (part) { result.set(part, offset); offset += part.length; }); return result;
  }
  function zip(files) {
    var local = [], directory = [], offset = 0;
    Object.keys(files).forEach(function (path) {
      var name = encoder.encode(path), data = encoder.encode(files[path]), checksum = crc(data);
      var record = header(30, [[0, 0x04034b50], [4, 20, 2], [6, 0x800, 2], [12, 33, 2],
        [14, checksum], [18, data.length], [22, data.length], [26, name.length, 2]]);
      local.push(record, name, data);
      directory.push(header(46, [[0, 0x02014b50], [4, 20, 2], [6, 20, 2], [8, 0x800, 2], [14, 33, 2],
        [16, checksum], [20, data.length], [24, data.length], [28, name.length, 2], [42, offset]]), name);
      offset += record.length + name.length + data.length;
    });
    var central = concat(directory), count = Object.keys(files).length;
    return concat(local.concat([central, header(22, [[0, 0x06054b50], [8, count, 2], [10, count, 2],
      [12, central.length], [16, offset]])]));
  }
  function xlsx(rows, merges, sheetName) {
    rows = checked(rows);
    var ns = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main';
    var name = String(sheetName || 'Table').replace(/[\[\]:*?\/\\']/g, ' ').trim().slice(0, 31) || 'Table';
    var sheet = '<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="' + ns + '"><sheetData>';
    rows.forEach(function (row, r) {
      sheet += '<row r="' + (r + 1) + '">';
      row.forEach(function (value, c) {
        if (value.length > 32767) { throw new Error('exportCellLimit'); }
        sheet += '<c r="' + column(c) + (r + 1) + '" t="inlineStr"><is><t xml:space="preserve">' + xml(value) + '</t></is></c>';
      }); sheet += '</row>';
    });
    sheet += '</sheetData>';
    if (merges && merges.length) {
      sheet += '<mergeCells count="' + merges.length + '">' + merges.map(function (ref) {
        if (!/^[A-Z]+[1-9][0-9]*:[A-Z]+[1-9][0-9]*$/.test(ref)) { throw new Error('exportFailed'); }
        return '<mergeCell ref="' + ref + '"/>';
      }).join('') + '</mergeCells>';
    }
    sheet += '</worksheet>';
    var files = {
      '[Content_Types].xml': '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>',
      '_rels/.rels': '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
      'xl/workbook.xml': '<?xml version="1.0"?><workbook xmlns="' + ns + '" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="' + xml(name) + '" sheetId="1" r:id="rId1"/></sheets></workbook>',
      'xl/_rels/workbook.xml.rels': '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>',
      'xl/styles.xml': '<?xml version="1.0"?><styleSheet xmlns="' + ns + '"><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="1"><xf xfId="0"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>',
      'xl/worksheets/sheet1.xml': sheet
    };
    return zip(files);
  }
  var api = { csv: csv, xlsx: xlsx, column: column };
  if (typeof module !== 'undefined' && module.exports) { module.exports = api; }
  else { root.LuxPediaTableExport = api; }
})(typeof window === 'undefined' ? globalThis : window);
