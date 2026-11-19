(function () {
  'use strict';
  if (window.LuxPediaTables) { return; }
  var t = window.LuxPediaI18n.t, instances = [], sequence = 0;
  function label(tag, key) {
    var node = document.createElement(tag); node.dataset.luxpediaI18n = key; node.textContent = t(key); return node;
  }
  function cellText(cell) {
    var source = cell;
    if (cell.querySelector('.luxpedia-drawio-preview')) { source = cell.cloneNode(true); source.querySelectorAll('.luxpedia-drawio-preview').forEach(function (node) { node.remove(); }); }
    var value = source.textContent.trim();
    return value || Array.from(source.querySelectorAll('img[alt]')).map(function (image) { return image.alt; }).join(' ');
  }
  function grid(table) {
    var source = Array.from(table.rows).filter(function (row) { return row.closest('table') === table && !row.hasAttribute('data-luxpedia-empty'); });
    var values = [], merges = [], slots = 0;
    source.forEach(function (row, r) {
      if (!values[r]) { values[r] = []; }
      var c = 0;
      Array.from(row.cells).forEach(function (cell) {
        while (values[r][c] !== undefined) { c++; }
        var height = Math.min(cell.rowSpan || source.length - r, source.length - r), width = cell.colSpan || 1;
        slots += height * width;
        if (slots > 250000 || c + width > 16384) { throw new Error('exportLimit'); }
        for (var y = r; y < r + height; y++) {
          if (!values[y]) { values[y] = []; }
          for (var x = c; x < c + width; x++) { values[y][x] = y === r && x === c ? cellText(cell) : ''; }
        }
        if (height > 1 || width > 1) {
          merges.push(LuxPediaTableExport.column(c) + (r + 1) + ':' + LuxPediaTableExport.column(c + width - 1) + (r + height));
        }
        c += width;
      });
    });
    var columns = values.reduce(function (max, row) { return Math.max(max, row.length); }, 0);
    values = values.map(function (row) { return Array.from({ length: columns }, function (_, i) { return row[i] || ''; }); });
    var headers = source.filter(function (row) { return row.parentNode.tagName === 'THEAD'; }).length;
    if (!headers && source[0] && Array.from(source[0].cells).every(function (cell) { return cell.tagName === 'TH'; })) { headers = 1; }
    return { source: source, values: values, merges: merges, headers: headers, columns: columns };
  }
  function enhance(table) {
    if (table.dataset.luxpediaTableBound) { return; }
    var metadata = table.hasAttribute('data-luxpedia-browser');
    if (!metadata && (table.matches('.luxpedia-system-list, .xwiki-livetable, .livedata') ||
        table.closest('.xwiki-livetable, .livedata, [contenteditable="true"], pre') ||
        Array.from(table.querySelectorAll('table, input, select, textarea, button')).some(function (node) { return !node.closest('.luxpedia-drawio-preview'); }) || table.rows.length < 2)) { return; }
    table.dataset.luxpediaTableBound = '1';
    var model;
    try { model = grid(table); } catch (error) { return; }
    if (!model.columns) { return; }
    var prefix = 'luxpedia-table-' + (++sequence), merged = model.merges.length > 0;
    var headerRows = model.values.slice(0, model.headers);
    var footers = model.source.map(function (row, index) { return { node: row, cells: model.values[index] }; }).filter(function (row) { return row.node.parentNode.tagName === 'TFOOT'; });
    var names = Array.from({ length: model.columns }, function (_, i) {
      return headerRows.map(function (row) { return row[i]; }).filter(Boolean).join(' / ') || t('tableColumn').replace('{number}', i + 1);
    });
    var rows = model.source.slice(model.headers).map(function (node, i) {
      return { node: node, cells: model.values[i + model.headers], index: i };
    }).filter(function (row) { return !row.node.hasAttribute('data-luxpedia-empty') && row.node.parentNode.tagName !== 'TFOOT' &&
      !row.node.hidden && row.node.style.display !== 'none'; });
    var shell = document.createElement('section'); shell.className = 'luxpedia-table-tools';
    shell.style.cssText = 'margin:1em 0;max-width:100%;';
    var toolbar = document.createElement('div'); toolbar.className = 'form-inline';
    toolbar.style.cssText = 'display:flex;flex-wrap:wrap;gap:8px;align-items:end;margin-bottom:8px;';
    var scroll = document.createElement('div'); scroll.style.cssText = 'overflow-x:auto;max-width:100%;';
    table.parentNode.insertBefore(shell, table); shell.appendChild(toolbar); shell.appendChild(scroll); scroll.appendChild(table);
    var pager = document.createElement('div'); pager.className = 'form-inline'; pager.style.cssText = 'display:flex;gap:8px;flex-wrap:wrap;align-items:center;';
    shell.appendChild(pager);
    var status = document.createElement('p'); status.setAttribute('role', 'status'); status.className = 'xHint'; shell.appendChild(status);
    var controls = {};
    function field(key, kind, options) {
      var group = document.createElement('div'); group.className = 'form-group'; group.style.cssText = 'max-width:100%;min-width:0;';
      var caption = label('label', key); caption.htmlFor = prefix + '-' + key; caption.style.display = 'block';
      var input = document.createElement(kind === 'select' ? 'select' : 'input'); input.className = 'form-control input-sm'; input.style.maxWidth = '100%'; input.id = caption.htmlFor;
      if (kind !== 'select') { input.type = kind; }
      if (kind === 'search') { input.style.maxWidth = '100%'; }
      (options || []).forEach(function (entry) {
        var option = document.createElement('option'); option.value = entry[0];
        option.textContent = entry[2] ? t(entry[1]) : entry[1]; if (entry[2]) { option.dataset.luxpediaI18n = entry[1]; }
        input.appendChild(option);
      });
      group.appendChild(caption); group.appendChild(input); toolbar.appendChild(group); controls[key] = input; return input;
    }
    var search = field('tableSearch', 'search');
    function values(column) { return Array.from(new Set(rows.map(function (row) { return row.cells[column]; }).filter(Boolean))).sort(); }
    if (metadata) {
      var areas = Array.from(new Set(rows.map(function (row) { return row.node.dataset.area; }))).filter(Boolean);
      var area = field('browserArea', 'select', [['', 'browserAllAreas', true]].concat(areas.map(function (value) {
        return [value, value === 'Drafts' ? 'drafts' : 'knowledge', true];
      })));
      if (areas.includes('Knowledge')) { area.value = 'Knowledge'; }
      field('browserCategory', 'select', [['', 'tableAll', true]].concat(values(1).map(function (value) { return [value, value]; })));
      field('browserOwner', 'select', [['', 'tableAll', true]].concat(values(2).map(function (value) { return [value, value]; })));
      var states = Array.from(new Set(rows.map(function (row) { return row.node.dataset.state; }))).filter(Boolean);
      field('browserState', 'select', [['active', 'browserActive', true], ['', 'tableAll', true]].concat(states.map(function (state) {
        return [state, state === 'abandoned' ? 'abandonedTitle' : state, true];
      })));
      field('browserTags', 'search'); field('browserFrom', 'date'); field('browserTo', 'date');
      area.addEventListener('change', function () { controls.browserState.value = 'active'; });
    }
    var sort = field('tableSort', 'select', [['', 'tableOriginal', true]].concat(names.map(function (name, i) { return [String(i), name]; })));
    var descending = false;
    function button(parent, id, key, handler) {
      var node = label('button', key); node.type = 'button'; node.className = 'btn btn-default btn-sm';
      node.id = prefix + '-' + id; node.addEventListener('click', handler); parent.appendChild(node); return node;
    }
    var direction = button(toolbar, 'direction', 'tableAscending', function () {
      descending = !descending; direction.dataset.luxpediaI18n = descending ? 'tableDescending' : 'tableAscending'; direction.textContent = t(direction.dataset.luxpediaI18n); page = 1; render();
    });
    button(toolbar, 'reset', 'tableReset', function () {
      Object.keys(controls).forEach(function (key) { controls[key].value = key === 'browserState' ? 'active' : ''; });
      if (controls.browserArea && Array.from(controls.browserArea.options).some(function (option) { return option.value === 'Knowledge'; })) { controls.browserArea.value = 'Knowledge'; }
      descending = false; direction.dataset.luxpediaI18n = 'tableAscending'; direction.textContent = t('tableAscending'); page = 1; render();
    });
    var title = table.caption && table.caption.textContent || document.title || 'Table';
    var filename = title.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim().slice(0, 80) || 'Table';
    var filtered = [], page = 1;
    function exported() {
      if (merged) { return { rows: model.values, merges: model.merges }; }
      return { rows: headerRows.concat(filtered.map(function (row) { return row.cells; })).concat(filtered.length === rows.length ? footers.map(function (row) { return row.cells; }) : []), merges: [] };
    }
    function download(kind) {
      try {
        var data = exported(), value = kind === 'csv' ? LuxPediaTableExport.csv(data.rows) : LuxPediaTableExport.xlsx(data.rows, data.merges, t('exportSheet'));
        var blob = new Blob([value], { type: kind === 'csv' ? 'text/csv;charset=utf-8' : 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
        var link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = filename + '.' + kind;
        document.body.appendChild(link); link.click(); link.remove(); setTimeout(function () { URL.revokeObjectURL(link.href); }, 60000);
      } catch (error) { status.textContent = t(['exportLimit', 'exportCellLimit'].includes(error.message) ? error.message : 'exportFailed'); }
    }
    button(toolbar, 'xlsx', 'exportExcel', function () { download('xlsx'); });
    button(toolbar, 'csv', 'exportCSV', function () { download('csv'); });
    var previous = button(pager, 'previous', 'tablePrevious', function () { page--; render(); });
    var pageCaption = label('label', 'tablePage'); pageCaption.htmlFor = prefix + '-page'; pager.appendChild(pageCaption);
    var pageInput = document.createElement('input'); pageInput.type = 'number'; pageInput.min = '1'; pageInput.id = pageCaption.htmlFor;
    pageInput.className = 'form-control input-sm'; pageInput.style.width = '5em'; pager.appendChild(pageInput);
    pageInput.addEventListener('change', function () { page = Number(pageInput.value) || 1; render(); });
    var next = button(pager, 'next', 'tableNext', function () { page++; render(); });
    var sizeCaption = label('label', 'tablePageSize'); sizeCaption.htmlFor = prefix + '-size'; pager.appendChild(sizeCaption);
    var size = document.createElement('select'); size.id = sizeCaption.htmlFor; size.className = 'form-control input-sm';
    [10, 25, 50, 100].forEach(function (n) { var option = document.createElement('option'); option.value = n; option.textContent = n; size.appendChild(option); });
    size.value = '25'; pager.appendChild(size); size.addEventListener('change', function () { page = 1; render(); });
    var empty = document.createElement('tr'); empty.className = 'luxpedia-table-empty'; var emptyCell = label('td', 'tableNoRows'); emptyCell.colSpan = model.columns;
    empty.appendChild(emptyCell); var body = table.tBodies[0] || table.createTBody(); body.appendChild(empty);
    Array.from(table.querySelectorAll('[data-luxpedia-empty]')).forEach(function (row) { row.style.display = 'none'; });
    function day(value) {
      if (!value) { return ''; } var date = new Date(Number(value)); if (!Number.isFinite(date.getTime())) { return ''; }
      return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0');
    }
    function normalized(value) { return String(value || '').toLocaleLowerCase(); }
    function render() {
      if (metadata) {
        if (table.tHead && table.tHead.rows[0]) {
          headerRows = [Array.from(table.tHead.rows[0].cells).map(cellText)];
          Array.from(sort.options).slice(1).forEach(function (option, index) { option.textContent = headerRows[0][index]; });
        }
        rows.forEach(function (row) {
          var state = row.node.querySelector('[data-luxpedia-state]');
          if (state) { state.textContent = t(state.dataset.luxpediaState === 'abandoned' ? 'abandonedTitle' : state.dataset.luxpediaState); row.cells[3] = state.textContent; }
          var updated = row.node.querySelector('[data-luxpedia-updated]');
          if (updated && day(updated.dataset.luxpediaUpdated)) { updated.textContent = new Date(Number(updated.dataset.luxpediaUpdated)).toLocaleString(window.LuxPediaI18n.language() === 'zh' ? 'zh-CN' : 'en-GB'); row.cells[5] = updated.textContent; }
        });
      }
      filtered = rows.filter(function (row) {
        if (!normalized(row.cells.join(' ')).includes(normalized(search.value))) { return false; }
        if (!metadata) { return true; }
        var state = controls.browserState.value, date = day(row.node.dataset.updated);
        return (!controls.browserArea.value || row.node.dataset.area === controls.browserArea.value) &&
          (!controls.browserCategory.value || row.cells[1] === controls.browserCategory.value) &&
          (!controls.browserOwner.value || row.cells[2] === controls.browserOwner.value) &&
          (state === 'active' ? !['archived', 'abandoned'].includes(row.node.dataset.state) : !state || row.node.dataset.state === state) &&
          normalized(row.cells[4]).includes(normalized(controls.browserTags.value)) &&
          (!controls.browserFrom.value || date && date >= controls.browserFrom.value) && (!controls.browserTo.value || date && date <= controls.browserTo.value);
      });
      if (controls.browserFrom && controls.browserFrom.value && controls.browserTo.value && controls.browserFrom.value > controls.browserTo.value) { filtered = []; }
      if (sort.value !== '') {
        var index = Number(sort.value), collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });
        filtered.sort(function (a, b) {
          var result = metadata && index === 5 ? Number(a.node.dataset.updated || 0) - Number(b.node.dataset.updated || 0) : collator.compare(a.cells[index], b.cells[index]);
          return (descending ? -result : result) || a.index - b.index;
        });
      }
      var pages = Math.max(1, Math.ceil(filtered.length / Number(size.value))); page = Math.max(1, Math.min(pages, Math.floor(page)));
      if (!merged) {
        footers.forEach(function (row) { row.node.style.display = filtered.length === rows.length ? '' : 'none'; });
        rows.forEach(function (row) { row.node.style.display = 'none'; });
        filtered.forEach(function (row, i) { body.appendChild(row.node); row.node.style.display = i >= (page - 1) * Number(size.value) && i < page * Number(size.value) ? '' : 'none'; });
      }
      empty.style.display = filtered.length || merged ? 'none' : '';
      pageInput.value = page; pageInput.max = pages; previous.disabled = page <= 1; next.disabled = page >= pages;
      status.textContent = merged ? t('tableMerged') : t('tableResults').replace('{count}', filtered.length).replace('{page}', page).replace('{pages}', pages) + ' · ' + t('tableExportHint');
    }
    Object.values(controls).forEach(function (node) { node.addEventListener(node.type === 'search' ? 'input' : 'change', function () { page = 1; render(); }); });
    if (merged) {
      Object.values(controls).forEach(function (node) { node.disabled = true; }); direction.disabled = true; pager.style.display = 'none';
    }
    instances.push({ render: render, table: table }); render();
  }
  function boot() {
    if (!/\/bin\/view\//.test(location.pathname)) { return; }
    var content = document.getElementById('xwikicontent'); if (!content) { return; }
    content.querySelectorAll('table').forEach(enhance);
  }
  window.LuxPediaTables = { scan: boot };
  document.addEventListener('luxpedia:languagechange', function () { instances.forEach(function (instance) { instance.render(); }); });
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', boot); } else { boot(); }
})();
